from datetime import datetime

from sqlalchemy import BigInteger, DateTime, PrimaryKeyConstraint, String, Text, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class Notification(Base):
    """Persisted record of a notification sent (or attempted) for a domain event."""

    __tablename__ = "notifications"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    # NotificationType: ORDER_CONFIRMATION / PAYMENT_RECEIPT / PAYMENT_FAILED / PAYMENT_CANCELLED
    type: Mapped[str] = mapped_column(String(40), nullable=False)
    # Channel: always EMAIL for Wave 3
    channel: Mapped[str] = mapped_column(String(20), nullable=False, default="EMAIL")
    # Status: SENT (sandbox) / PENDING_RECIPIENT (no address yet) / FAILED
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    subject: Mapped[str] = mapped_column(String(200), nullable=False)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    # Links this notification back to the originating event (orderId / paymentId)
    dedup_key: Mapped[str] = mapped_column(String(200), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class ProcessedEvent(Base):
    """Inbox table for idempotent event consumption.

    PK (dedup_key, event_type) ensures each unique domain event is processed exactly once.
    """

    __tablename__ = "processed_events"
    __table_args__ = (PrimaryKeyConstraint("dedup_key", "event_type"),)

    dedup_key: Mapped[str] = mapped_column(String(200), nullable=False)
    event_type: Mapped[str] = mapped_column(String(100), nullable=False)
    processed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
