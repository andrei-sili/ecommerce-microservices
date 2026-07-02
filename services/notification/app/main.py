import asyncio
import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from app.api.health import router as health_router
from app.core.config import settings
from app.core.database import create_engine, create_session_factory
from app.services.consumer import run_consumer
from app.services.email_sender import SandboxEmailSender

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    consumer_task: asyncio.Task[None] | None = None
    shutdown_event: asyncio.Event | None = None

    if not settings.SKIP_CONSUMER:
        engine = create_engine(settings.DATABASE_URL)
        session_factory = create_session_factory(engine)
        sender = SandboxEmailSender()
        shutdown_event = asyncio.Event()
        consumer_task = asyncio.create_task(
            run_consumer(
                rabbitmq_url=settings.broker_url,
                session_factory=session_factory,
                sender=sender,
                max_retries=settings.MAX_RETRIES,
                shutdown_event=shutdown_event,
            )
        )
        logger.info("Consumer task started")
    else:
        logger.info("SKIP_CONSUMER=true — RabbitMQ consumer not started")

    yield

    if shutdown_event is not None:
        shutdown_event.set()
    if consumer_task is not None:
        try:
            await asyncio.wait_for(consumer_task, timeout=10.0)
        except (asyncio.CancelledError, TimeoutError):
            consumer_task.cancel()


app = FastAPI(title="Notification Service", version="0.1.0", lifespan=lifespan)
app.include_router(health_router)

Instrumentator().instrument(app).expose(app, include_in_schema=False)
