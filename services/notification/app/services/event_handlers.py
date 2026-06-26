"""Domain event handlers: parse camelCase payload, deduplicate, persist, send email.

Each handler:
  1. Checks the processed_events inbox — if found, returns immediately (idempotent).
  2. Renders email content.
  3. Calls the EmailSender (sandbox: logs + returns 'SENT').
  4. Commits Notification + ProcessedEvent in one transaction.
  5. Returns — caller is responsible for acking the message after this succeeds.
"""

import logging
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import Notification, ProcessedEvent
from app.models.events import (
    OrderPlacedEvent,
    PaymentCancelledEvent,
    PaymentCompletedEvent,
    PaymentFailedEvent,
)
from app.services.email_sender import EmailMessage, EmailSender

logger = logging.getLogger(__name__)


async def _is_already_processed(session: AsyncSession, dedup_key: str, event_type: str) -> bool:
    result = await session.execute(
        select(ProcessedEvent)
        .where(ProcessedEvent.dedup_key == dedup_key)
        .where(ProcessedEvent.event_type == event_type)
    )
    return result.scalar_one_or_none() is not None


async def _commit_notification(
    session: AsyncSession,
    user_id: int,
    notification_type: str,
    subject: str,
    body: str,
    dedup_key: str,
    status: str,
    event_type: str,
) -> None:
    """Persist Notification + ProcessedEvent in one transaction."""
    session.add(
        Notification(
            user_id=user_id,
            type=notification_type,
            channel="EMAIL",
            status=status,
            subject=subject,
            body=body,
            dedup_key=dedup_key,
        )
    )
    session.add(
        ProcessedEvent(
            dedup_key=dedup_key,
            event_type=event_type,
        )
    )
    await session.commit()


async def handle_order_placed(
    event: OrderPlacedEvent,
    session: AsyncSession,
    sender: EmailSender,
) -> None:
    """OrderPlaced → ORDER_CONFIRMATION email."""
    event_type = "OrderPlaced"
    dedup_key = event.order_id

    if await _is_already_processed(session, dedup_key, event_type):
        logger.info("Duplicate %s/%s, skipping", event_type, dedup_key)
        return

    lines = "\n".join(
        f"  - Product {item.product_id}: qty {item.quantity} @ "
        f"{item.unit_price} {event.currency}"
        for item in event.items
    )
    subject = f"Order Confirmation - Order #{event.order_id}"
    body = (
        f"Thank you for your order!\n\n"
        f"Order ID: {event.order_id}\n"
        f"Items:\n{lines}\n"
        f"Total: {_fmt_money(event.total)} {event.currency}\n"
        f"Placed at: {event.occurred_at.isoformat()}\n"
    )

    email = EmailMessage(
        to_user_id=event.user_id,
        subject=subject,
        body=body,
        notification_type="ORDER_CONFIRMATION",
        dedup_key=dedup_key,
    )
    status = await sender.send(email)

    await _commit_notification(
        session,
        user_id=event.user_id,
        notification_type="ORDER_CONFIRMATION",
        subject=subject,
        body=body,
        dedup_key=dedup_key,
        status=status,
        event_type=event_type,
    )
    logger.info("Processed %s/%s → status=%s", event_type, dedup_key, status)


async def handle_payment_completed(
    event: PaymentCompletedEvent,
    session: AsyncSession,
    sender: EmailSender,
) -> None:
    """PaymentCompleted → PAYMENT_RECEIPT email."""
    event_type = "PaymentCompleted"
    dedup_key = event.payment_id

    if await _is_already_processed(session, dedup_key, event_type):
        logger.info("Duplicate %s/%s, skipping", event_type, dedup_key)
        return

    subject = f"Payment Receipt - Payment #{event.payment_id}"
    body = (
        f"Your payment has been received.\n\n"
        f"Payment ID: {event.payment_id}\n"
        f"Order ID: {event.order_id}\n"
        f"Amount: {_fmt_money(event.amount)} {event.currency}\n"
        f"Status: {event.status}\n"
        f"Processed at: {event.occurred_at.isoformat()}\n"
    )

    email = EmailMessage(
        to_user_id=event.user_id,
        subject=subject,
        body=body,
        notification_type="PAYMENT_RECEIPT",
        dedup_key=dedup_key,
    )
    status = await sender.send(email)

    await _commit_notification(
        session,
        user_id=event.user_id,
        notification_type="PAYMENT_RECEIPT",
        subject=subject,
        body=body,
        dedup_key=dedup_key,
        status=status,
        event_type=event_type,
    )
    logger.info("Processed %s/%s → status=%s", event_type, dedup_key, status)


async def handle_payment_failed(
    event: PaymentFailedEvent,
    session: AsyncSession,
    sender: EmailSender,
) -> None:
    """PaymentFailed → PAYMENT_FAILED email."""
    event_type = "PaymentFailed"
    dedup_key = event.payment_id

    if await _is_already_processed(session, dedup_key, event_type):
        logger.info("Duplicate %s/%s, skipping", event_type, dedup_key)
        return

    subject = "Payment Failed - Action Required"
    body = (
        f"Unfortunately your payment could not be processed.\n\n"
        f"Payment ID: {event.payment_id}\n"
        f"Order ID: {event.order_id}\n"
        f"Amount: {_fmt_money(event.amount)} {event.currency}\n"
        f"Reason: {event.failure_reason}\n"
        f"At: {event.occurred_at.isoformat()}\n\n"
        f"Please try again with a different payment method.\n"
    )

    email = EmailMessage(
        to_user_id=event.user_id,
        subject=subject,
        body=body,
        notification_type="PAYMENT_FAILED",
        dedup_key=dedup_key,
    )
    status = await sender.send(email)

    await _commit_notification(
        session,
        user_id=event.user_id,
        notification_type="PAYMENT_FAILED",
        subject=subject,
        body=body,
        dedup_key=dedup_key,
        status=status,
        event_type=event_type,
    )
    logger.info("Processed %s/%s → status=%s", event_type, dedup_key, status)


async def handle_payment_cancelled(
    event: PaymentCancelledEvent,
    session: AsyncSession,
    sender: EmailSender,
) -> None:
    """PaymentCancelled → PAYMENT_CANCELLED email."""
    event_type = "PaymentCancelled"
    dedup_key = event.payment_id

    if await _is_already_processed(session, dedup_key, event_type):
        logger.info("Duplicate %s/%s, skipping", event_type, dedup_key)
        return

    subject = "Payment Cancelled"
    body = (
        f"Your payment has been cancelled.\n\n"
        f"Payment ID: {event.payment_id}\n"
        f"Order ID: {event.order_id}\n"
        f"Amount: {_fmt_money(event.amount)} {event.currency}\n"
        f"Cancelled at: {event.occurred_at.isoformat()}\n"
    )

    email = EmailMessage(
        to_user_id=event.user_id,
        subject=subject,
        body=body,
        notification_type="PAYMENT_CANCELLED",
        dedup_key=dedup_key,
    )
    status = await sender.send(email)

    await _commit_notification(
        session,
        user_id=event.user_id,
        notification_type="PAYMENT_CANCELLED",
        subject=subject,
        body=body,
        dedup_key=dedup_key,
        status=status,
        event_type=event_type,
    )
    logger.info("Processed %s/%s → status=%s", event_type, dedup_key, status)


def _fmt_money(amount: Decimal) -> str:
    return f"{amount:.2f}"
