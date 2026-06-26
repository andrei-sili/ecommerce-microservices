"""Consumer handler tests — no RabbitMQ required; handlers are called directly.

Covers:
  1. OrderPlaced → creates exactly one notifications row + one processed_events row.
  2. Second delivery of the same OrderPlaced → NO second row (idempotency).
  3. PaymentCompleted → creates a PAYMENT_RECEIPT notification.
  4. PaymentFailed → creates a PAYMENT_FAILED notification.
  5. PaymentCancelled → creates a PAYMENT_CANCELLED notification.
"""

from datetime import UTC, datetime
from decimal import Decimal

import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import Notification, ProcessedEvent
from app.models.events import (
    OrderItem,
    OrderPlacedEvent,
    PaymentCancelledEvent,
    PaymentCompletedEvent,
    PaymentFailedEvent,
)
from app.services.email_sender import SandboxEmailSender
from app.services.event_handlers import (
    handle_order_placed,
    handle_payment_cancelled,
    handle_payment_completed,
    handle_payment_failed,
)

NOW = datetime(2026, 6, 26, 10, 0, 0, tzinfo=UTC)


def _order_placed(order_id: str = "ord-001") -> OrderPlacedEvent:
    return OrderPlacedEvent.model_validate(
        {
            "orderId": order_id,
            "userId": 42,
            "items": [{"productId": 7, "quantity": 2, "unitPrice": "19.99"}],
            "total": "39.98",
            "currency": "EUR",
            "occurredAt": NOW.isoformat(),
        }
    )


def _payment_completed(payment_id: str = "pay-001") -> PaymentCompletedEvent:
    return PaymentCompletedEvent.model_validate(
        {
            "paymentId": payment_id,
            "orderId": "ord-001",
            "userId": 42,
            "amount": "39.98",
            "currency": "EUR",
            "status": "SUCCEEDED",
            "occurredAt": NOW.isoformat(),
        }
    )


def _payment_failed(payment_id: str = "pay-002") -> PaymentFailedEvent:
    return PaymentFailedEvent.model_validate(
        {
            "paymentId": payment_id,
            "orderId": "ord-001",
            "userId": 42,
            "amount": "39.98",
            "currency": "EUR",
            "status": "FAILED",
            "failureReason": "CARD_DECLINED",
            "occurredAt": NOW.isoformat(),
        }
    )


def _payment_cancelled(payment_id: str = "pay-003") -> PaymentCancelledEvent:
    return PaymentCancelledEvent.model_validate(
        {
            "paymentId": payment_id,
            "orderId": "ord-001",
            "userId": 42,
            "amount": "39.98",
            "currency": "EUR",
            "status": "CANCELLED",
            "occurredAt": NOW.isoformat(),
        }
    )


async def _count_notifications(session: AsyncSession, dedup_key: str) -> int:
    result = await session.execute(select(func.count()).where(Notification.dedup_key == dedup_key))
    return result.scalar_one()


async def _count_processed(session: AsyncSession, dedup_key: str, event_type: str) -> int:
    result = await session.execute(
        select(func.count())
        .where(ProcessedEvent.dedup_key == dedup_key)
        .where(ProcessedEvent.event_type == event_type)
    )
    return result.scalar_one()


@pytest.mark.asyncio
async def test_order_placed_creates_notification(db_session: AsyncSession) -> None:
    sender = SandboxEmailSender()
    event = _order_placed("ord-100")

    await handle_order_placed(event, db_session, sender)

    assert await _count_notifications(db_session, "ord-100") == 1
    assert await _count_processed(db_session, "ord-100", "OrderPlaced") == 1

    # Verify notification content
    result = await db_session.execute(
        select(Notification).where(Notification.dedup_key == "ord-100")
    )
    notification = result.scalar_one()
    assert notification.type == "ORDER_CONFIRMATION"
    assert notification.channel == "EMAIL"
    assert notification.status == "SENT"
    assert notification.user_id == 42
    assert "ord-100" in notification.subject


@pytest.mark.asyncio
async def test_order_placed_idempotent(db_session: AsyncSession) -> None:
    """Second delivery of the same OrderPlaced must not create a second row."""
    sender = SandboxEmailSender()
    event = _order_placed("ord-200")

    # First delivery
    await handle_order_placed(event, db_session, sender)
    # Second delivery (simulating RabbitMQ redelivery)
    await handle_order_placed(event, db_session, sender)

    assert await _count_notifications(db_session, "ord-200") == 1
    assert await _count_processed(db_session, "ord-200", "OrderPlaced") == 1


@pytest.mark.asyncio
async def test_payment_completed_creates_receipt(db_session: AsyncSession) -> None:
    sender = SandboxEmailSender()
    event = _payment_completed("pay-100")

    await handle_payment_completed(event, db_session, sender)

    assert await _count_notifications(db_session, "pay-100") == 1
    result = await db_session.execute(
        select(Notification).where(Notification.dedup_key == "pay-100")
    )
    notification = result.scalar_one()
    assert notification.type == "PAYMENT_RECEIPT"
    assert notification.status == "SENT"


@pytest.mark.asyncio
async def test_payment_completed_idempotent(db_session: AsyncSession) -> None:
    sender = SandboxEmailSender()
    event = _payment_completed("pay-200")

    await handle_payment_completed(event, db_session, sender)
    await handle_payment_completed(event, db_session, sender)

    assert await _count_notifications(db_session, "pay-200") == 1


@pytest.mark.asyncio
async def test_payment_failed_creates_notification(db_session: AsyncSession) -> None:
    sender = SandboxEmailSender()
    event = _payment_failed("pay-300")

    await handle_payment_failed(event, db_session, sender)

    assert await _count_notifications(db_session, "pay-300") == 1
    result = await db_session.execute(
        select(Notification).where(Notification.dedup_key == "pay-300")
    )
    notification = result.scalar_one()
    assert notification.type == "PAYMENT_FAILED"
    assert notification.status == "SENT"


@pytest.mark.asyncio
async def test_payment_cancelled_creates_notification(db_session: AsyncSession) -> None:
    sender = SandboxEmailSender()
    event = _payment_cancelled("pay-400")

    await handle_payment_cancelled(event, db_session, sender)

    assert await _count_notifications(db_session, "pay-400") == 1
    result = await db_session.execute(
        select(Notification).where(Notification.dedup_key == "pay-400")
    )
    notification = result.scalar_one()
    assert notification.type == "PAYMENT_CANCELLED"
    assert notification.status == "SENT"


@pytest.mark.asyncio
async def test_event_payload_camelcase_deserialization() -> None:
    """Verify camelCase aliases are explicitly configured (not relying on defaults)."""
    # OrderItem uses explicit alias "productId" not "product_id"
    item = OrderItem.model_validate({"productId": 7, "quantity": 2, "unitPrice": "9.99"})
    assert item.product_id == 7
    assert item.unit_price == Decimal("9.99")

    # OrderPlacedEvent uses explicit aliases
    event = OrderPlacedEvent.model_validate(
        {
            "orderId": "test-123",
            "userId": 1,
            "items": [],
            "total": "0.00",
            "currency": "EUR",
            "occurredAt": NOW.isoformat(),
        }
    )
    assert event.order_id == "test-123"
    assert event.user_id == 1
