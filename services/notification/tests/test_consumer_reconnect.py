"""Non-mocked reconnection regression guard for the RabbitMQ consumer.

This is a REGRESSION GUARD + observability check, NOT a data-loss fix. It was
empirically proven that aio-pika's ``connect_robust`` already re-declares the
exchange/queue/binding and re-attaches the consumer after a full broker wipe.
This test locks that behaviour in and asserts the ONE thing a wrong "fix" would
break: exactly one consumer on the restored queue (``consumers == 1``).

If someone later adds a harmful reconnect callback that also re-declares/re-consumes,
the queue reappears with ``consumers == 2`` (split delivery / double emails) and this
test goes RED.

Design notes (from the verified probe recipe):
  - Real RabbitMQ, no mocks; the REAL ``run_consumer`` runs as a task.
  - ``connect_robust`` reconnects to the SAME URL, so the replacement broker MUST
    bind the SAME fixed host port (5672) — a random mapped port would never be
    reconnected to.
  - Wiped-broker restart (``docker rm -f`` + fresh ``docker run``) forces both the
    reconnect AND proves topology is re-declared server-side.
  - Poll-with-deadline everywhere; never fixed sleeps (reconnect_interval ~5s plus a
    variable image/broker start).
"""

import asyncio
import json
import subprocess
import time
from collections.abc import AsyncGenerator
from contextlib import suppress
from uuid import uuid4

import aio_pika
import pytest
import pytest_asyncio
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.models.db import Base, Notification
from app.services.consumer import EXCHANGE_NAME, QUEUE_ORDER, run_consumer
from app.services.email_sender import SandboxEmailSender

pytestmark = pytest.mark.rabbitmq

BROKER_CONTAINER = "notif-reconnect-rabbit"
BROKER_IMAGE = "rabbitmq:3-management-alpine"
BROKER_PORT = 5672
BROKER_URL = f"amqp://guest:guest@localhost:{BROKER_PORT}/"


def _docker_available() -> bool:
    """True only if the docker CLI is genuinely usable (the ONLY allowed skip reason)."""
    try:
        proc = subprocess.run(["docker", "version"], capture_output=True, text=True, timeout=15)
    except (FileNotFoundError, subprocess.SubprocessError):
        return False
    return proc.returncode == 0


def _docker(*args: str, timeout: int = 300) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)


def _start_broker_sync() -> None:
    """Remove any leftover container, then start a FRESH (empty) broker on the fixed port."""
    _docker("rm", "-f", BROKER_CONTAINER)
    result = _docker(
        "run",
        "-d",
        "--name",
        BROKER_CONTAINER,
        "-p",
        f"{BROKER_PORT}:5672",
        BROKER_IMAGE,
    )
    if result.returncode != 0:
        raise RuntimeError(f"failed to start broker: {result.stderr.strip()}")


def _rm_broker_sync() -> None:
    _docker("rm", "-f", BROKER_CONTAINER)


def _queue_consumers_sync(queue: str) -> int | None:
    """Consumer count for ``queue`` via rabbitmqctl, or None if the node/queue isn't ready."""
    result = _docker(
        "exec",
        BROKER_CONTAINER,
        "rabbitmqctl",
        "list_queues",
        "-q",
        "name",
        "consumers",
        timeout=30,
    )
    if result.returncode != 0:
        return None
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == queue:
            try:
                return int(parts[1])
            except ValueError:
                return None
    return None


async def _await_broker_ready(deadline: float) -> None:
    """Block until the broker accepts a real AMQP connection (image pull + startup)."""
    end = time.monotonic() + deadline
    last_exc: Exception | None = None
    while time.monotonic() < end:
        try:
            conn = await aio_pika.connect(BROKER_URL)
            await conn.close()
            return
        except Exception as exc:  # readiness probe swallows any transient connect error
            last_exc = exc
            await asyncio.sleep(1.0)
    raise TimeoutError(f"broker not ready within {deadline}s: {last_exc}")


async def _await_consumers_at_least(queue: str, minimum: int, deadline: float) -> int:
    """Poll until ``queue`` exists with consumers >= minimum. Returns the observed count."""
    end = time.monotonic() + deadline
    last: int | None = None
    while time.monotonic() < end:
        count = await asyncio.to_thread(_queue_consumers_sync, queue)
        if count is not None:
            last = count
            if count >= minimum:
                return count
        await asyncio.sleep(1.0)
    raise AssertionError(
        f"{queue!r} never reached consumers>={minimum} within {deadline}s (last seen: {last})"
    )


async def _await_notification(
    session_factory: async_sessionmaker, dedup_key: str, deadline: float
) -> int:
    """Poll the DB until a Notification with ``dedup_key`` appears. Returns the row count."""
    end = time.monotonic() + deadline
    while time.monotonic() < end:
        async with session_factory() as session:
            result = await session.execute(
                select(func.count())
                .select_from(Notification)
                .where(Notification.dedup_key == dedup_key)
            )
            count = result.scalar_one()
        if count >= 1:
            return count
        await asyncio.sleep(0.5)
    return 0


async def _publish_order_placed(order_id: str) -> None:
    """Publish one OrderPlaced (camelCase payload, unique tag) to the topic exchange."""
    connection = await aio_pika.connect(BROKER_URL)
    try:
        channel = await connection.channel()
        exchange = await channel.declare_exchange(
            EXCHANGE_NAME, aio_pika.ExchangeType.TOPIC, durable=True
        )
        payload = {
            "orderId": order_id,
            "userId": 42,
            "items": [{"productId": 7, "quantity": 2, "unitPrice": "19.99"}],
            "total": "39.98",
            "currency": "EUR",
            "occurredAt": "2026-07-01T10:00:00+00:00",
        }
        message = aio_pika.Message(
            body=json.dumps(payload).encode(),
            content_type="application/json",
            type="OrderPlaced",
            message_id=order_id,
            delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
        )
        await exchange.publish(message, routing_key="order.placed")
    finally:
        await connection.close()


@pytest_asyncio.fixture
async def reconnect_db(test_db_url: str) -> AsyncGenerator[async_sessionmaker, None]:
    """Own engine + fresh schema for the real consumer (session-based, not a single session)."""
    engine = create_async_engine(test_db_url, poolclass=NullPool)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    try:
        yield factory
    finally:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
        await engine.dispose()


async def test_consumer_reattaches_single_consumer_after_broker_wipe(
    reconnect_db: async_sessionmaker,
) -> None:
    """Wipe the broker, prove robust recovery re-declares topology with consumers==1.

    Steps:
      1. Fresh broker #1 → start the REAL run_consumer → wait for consumers>=1.
      2. Destroy broker #1, start a fresh EMPTY broker #2 on the same port 5672.
      3. Poll until notification.order-events reappears with consumers>=1 (this is BOTH
         the restore barrier AND the proof topology was re-declared server-side).
      4. Assert consumers==1 and stays 1 (non-vacuous: a harmful re-consume → 2).
      5. Publish an OrderPlaced AFTER the reconnect → assert its Notification row appears
         (zero loss, exactly one).
    """
    if not _docker_available():
        pytest.skip("docker CLI not available")

    shutdown = asyncio.Event()
    consumer_task: asyncio.Task[None] | None = None
    try:
        await asyncio.to_thread(_start_broker_sync)
        await _await_broker_ready(deadline=120)

        consumer_task = asyncio.create_task(
            run_consumer(
                rabbitmq_url=BROKER_URL,
                session_factory=reconnect_db,
                sender=SandboxEmailSender(),
                max_retries=5,
                shutdown_event=shutdown,
            )
        )

        # Barrier: consumer connected + attached to the order queue on broker #1.
        await _await_consumers_at_least(QUEUE_ORDER, minimum=1, deadline=45)

        # Wipe: destroy broker #1, bring up a fresh EMPTY broker on the SAME port.
        await asyncio.to_thread(_rm_broker_sync)
        await asyncio.to_thread(_start_broker_sync)
        await _await_broker_ready(deadline=120)

        # Restore barrier + topology-redeclared proof on the fresh broker.
        restored = await _await_consumers_at_least(QUEUE_ORDER, minimum=1, deadline=90)
        assert restored == 1, f"expected exactly one consumer after restore, saw {restored}"

        # Stability window: a harmful re-consume callback would surface as consumers==2.
        readings = [restored]
        for _ in range(4):
            await asyncio.sleep(1.0)
            sample = await asyncio.to_thread(_queue_consumers_sync, QUEUE_ORDER)
            if sample is not None:
                readings.append(sample)
        assert max(readings) == 1, f"expected consumers==1 throughout, saw {readings}"

        # Zero loss: an event published AFTER the reconnect is consumed end-to-end.
        order_id = f"reconnect-{uuid4()}"
        await _publish_order_placed(order_id)
        count = await _await_notification(reconnect_db, order_id, deadline=45)
        assert count == 1, f"expected exactly one Notification for {order_id}, got {count}"
    finally:
        shutdown.set()
        if consumer_task is not None:
            with suppress(TimeoutError, asyncio.CancelledError):
                await asyncio.wait_for(consumer_task, timeout=15)
            if not consumer_task.done():
                consumer_task.cancel()
                with suppress(asyncio.CancelledError):
                    await consumer_task
        await asyncio.to_thread(_rm_broker_sync)
