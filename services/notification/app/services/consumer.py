"""RabbitMQ consumer for notification events.

Topology (declared idempotently at startup):
  - Topic exchange:  ecommerce.events  (durable)
  - DLX exchange:    ecommerce.events.dlx  (durable)
  - Queue: notification.order-events    ← order.placed
  - Queue: notification.payment-events  ← payment.*
  - Each main queue dead-letters to <queue>.dlq on permanent failure.

Reliability:
  - Manual ack: ack only after handler + DB commit succeed.
  - Bounded retries (max MAX_RETRIES): transient errors republish with
    x-retry-count header; permanent errors or exhausted retries → nack(requeue=False)
    → DLX → DLQ.
  - Consumer is idempotent via processed_events inbox (see event_handlers.py).
"""

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable

import aio_pika
import aio_pika.abc
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.models.events import (
    OrderPlacedEvent,
    PaymentCancelledEvent,
    PaymentCompletedEvent,
    PaymentFailedEvent,
)
from app.services.email_sender import EmailSender
from app.services.event_handlers import (
    handle_order_placed,
    handle_payment_cancelled,
    handle_payment_completed,
    handle_payment_failed,
)

logger = logging.getLogger(__name__)

EXCHANGE_NAME = "ecommerce.events"
DLX_NAME = "ecommerce.events.dlx"
QUEUE_ORDER = "notification.order-events"
QUEUE_PAYMENT = "notification.payment-events"


async def _declare_topology(channel: aio_pika.abc.AbstractChannel) -> tuple[
    aio_pika.abc.AbstractExchange,
    aio_pika.abc.AbstractQueue,
    aio_pika.abc.AbstractQueue,
]:
    """Declare all exchanges and queues idempotently (durable, crash-safe)."""
    exchange = await channel.declare_exchange(
        EXCHANGE_NAME, aio_pika.ExchangeType.TOPIC, durable=True
    )
    dlx = await channel.declare_exchange(DLX_NAME, aio_pika.ExchangeType.TOPIC, durable=True)

    # Dead-letter queues (bound to DLX)
    order_dlq = await channel.declare_queue(f"{QUEUE_ORDER}.dlq", durable=True)
    await order_dlq.bind(dlx, f"{QUEUE_ORDER}.dlq")

    payment_dlq = await channel.declare_queue(f"{QUEUE_PAYMENT}.dlq", durable=True)
    await payment_dlq.bind(dlx, f"{QUEUE_PAYMENT}.dlq")

    # Main queues with DLQ wiring
    order_queue = await channel.declare_queue(
        QUEUE_ORDER,
        durable=True,
        arguments={
            "x-dead-letter-exchange": DLX_NAME,
            "x-dead-letter-routing-key": f"{QUEUE_ORDER}.dlq",
        },
    )
    await order_queue.bind(exchange, "order.placed")

    payment_queue = await channel.declare_queue(
        QUEUE_PAYMENT,
        durable=True,
        arguments={
            "x-dead-letter-exchange": DLX_NAME,
            "x-dead-letter-routing-key": f"{QUEUE_PAYMENT}.dlq",
        },
    )
    await payment_queue.bind(exchange, "payment.*")

    return exchange, order_queue, payment_queue


def _make_handler(
    routing_key_handlers: dict[
        str,
        Callable[[aio_pika.abc.AbstractIncomingMessage, AsyncSession], Awaitable[None]],
    ],
    exchange: aio_pika.abc.AbstractExchange,
    session_factory: async_sessionmaker[AsyncSession],
    max_retries: int,
) -> Callable[[aio_pika.abc.AbstractIncomingMessage], Awaitable[None]]:
    """Return a message callback that dispatches by event type, with retry/DLQ logic."""

    async def on_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
        event_type = message.type or "unknown"
        retry_count = int(str((message.headers or {}).get("x-retry-count", 0)))
        message_id = message.message_id or "?"

        try:
            handler = routing_key_handlers.get(message.routing_key or "")
            if handler is None:
                logger.warning("No handler for routing key %r, dead-lettering", message.routing_key)
                await message.nack(requeue=False)
                return

            async with session_factory() as session:
                await handler(message, session)

            await message.ack()
            logger.debug("Acked %s/%s", event_type, message_id)

        except _PermanentError as exc:
            logger.error(
                "Permanent error for %s/%s, dead-lettering: %s",
                event_type,
                message_id,
                exc,
            )
            await message.nack(requeue=False)

        except Exception as exc:
            # Transient error: republish with incremented retry counter
            if retry_count < max_retries:
                logger.warning(
                    "Transient error for %s/%s (attempt %d/%d): %s — retrying",
                    event_type,
                    message_id,
                    retry_count + 1,
                    max_retries,
                    exc,
                )
                new_headers = dict(message.headers or {})
                new_headers["x-retry-count"] = retry_count + 1
                await exchange.publish(
                    aio_pika.Message(
                        body=message.body,
                        content_type="application/json",
                        delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                        headers=new_headers,
                        type=message.type,
                        message_id=message.message_id,
                    ),
                    routing_key=message.routing_key or "",
                )
                await message.ack()  # ack original; retry is the new message
            else:
                logger.error(
                    "Max retries (%d) exceeded for %s/%s, dead-lettering: %s",
                    max_retries,
                    event_type,
                    message_id,
                    exc,
                )
                await message.nack(requeue=False)

    return on_message


class _PermanentError(Exception):
    """Raised for non-retryable failures (validation, poison message)."""


async def _dispatch_order(
    message: aio_pika.abc.AbstractIncomingMessage,
    session: AsyncSession,
    sender: EmailSender,
) -> None:
    try:
        payload = json.loads(message.body.decode())
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise _PermanentError(f"Cannot decode message body: {exc}") from exc

    event_type = message.type or message.routing_key or ""

    if event_type in ("OrderPlaced", "order.placed") or message.routing_key == "order.placed":
        try:
            event = OrderPlacedEvent.model_validate(payload)
        except Exception as exc:
            raise _PermanentError(f"Invalid OrderPlaced payload: {exc}") from exc
        await handle_order_placed(event, session, sender)
    else:
        raise _PermanentError(f"Unknown event type on order queue: {event_type!r}")


async def _dispatch_payment(
    message: aio_pika.abc.AbstractIncomingMessage,
    session: AsyncSession,
    sender: EmailSender,
) -> None:
    try:
        payload = json.loads(message.body.decode())
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise _PermanentError(f"Cannot decode message body: {exc}") from exc

    routing_key = message.routing_key or ""

    if routing_key == "payment.completed":
        try:
            completed_event = PaymentCompletedEvent.model_validate(payload)
        except Exception as exc:
            raise _PermanentError(f"Invalid PaymentCompleted payload: {exc}") from exc
        await handle_payment_completed(completed_event, session, sender)
    elif routing_key == "payment.failed":
        try:
            failed_event = PaymentFailedEvent.model_validate(payload)
        except Exception as exc:
            raise _PermanentError(f"Invalid PaymentFailed payload: {exc}") from exc
        await handle_payment_failed(failed_event, session, sender)
    elif routing_key == "payment.cancelled":
        try:
            cancelled_event = PaymentCancelledEvent.model_validate(payload)
        except Exception as exc:
            raise _PermanentError(f"Invalid PaymentCancelled payload: {exc}") from exc
        await handle_payment_cancelled(cancelled_event, session, sender)
    else:
        logger.info("Ignoring unhandled payment routing key %r", routing_key)


async def run_consumer(
    rabbitmq_url: str,
    session_factory: async_sessionmaker[AsyncSession],
    sender: EmailSender,
    max_retries: int,
    shutdown_event: asyncio.Event,
) -> None:
    """Connect to RabbitMQ, declare topology, and consume until shutdown_event is set."""
    logger.info("Consumer connecting to RabbitMQ")
    connection = await aio_pika.connect_robust(rabbitmq_url)
    try:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=10)

        exchange, order_queue, payment_queue = await _declare_topology(channel)

        # Build per-queue handlers (closures capture sender)
        async def order_message_handler(
            msg: aio_pika.abc.AbstractIncomingMessage,
            sess: AsyncSession,
        ) -> None:
            await _dispatch_order(msg, sess, sender)

        async def payment_message_handler(
            msg: aio_pika.abc.AbstractIncomingMessage,
            sess: AsyncSession,
        ) -> None:
            await _dispatch_payment(msg, sess, sender)

        order_cb = _make_handler(
            {"order.placed": order_message_handler},
            exchange,
            session_factory,
            max_retries,
        )
        payment_cb = _make_handler(
            {
                "payment.completed": payment_message_handler,
                "payment.failed": payment_message_handler,
                "payment.cancelled": payment_message_handler,
            },
            exchange,
            session_factory,
            max_retries,
        )

        await order_queue.consume(order_cb)
        await payment_queue.consume(payment_cb)

        logger.info("Consumer started — listening for events")
        await shutdown_event.wait()
        logger.info("Consumer shutting down")
    finally:
        await connection.close()
