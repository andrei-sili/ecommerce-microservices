"""Pydantic models for inbound RabbitMQ event payloads.

IMPORTANT: event payloads are camelCase (orderId, userId, etc.).
Aliases are set EXPLICITLY on every field — do NOT rely on library defaults.
This avoids the snake_case/camelCase split that caused a real 400 in Wave 2.
"""

from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field


class OrderItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    product_id: int = Field(alias="productId")
    quantity: int
    unit_price: Decimal = Field(alias="unitPrice")


class OrderPlacedEvent(BaseModel):
    """order.placed routing key — published by Order Service."""

    model_config = ConfigDict(populate_by_name=True)

    order_id: str = Field(alias="orderId")
    user_id: int = Field(alias="userId")
    items: list[OrderItem]
    total: Decimal
    currency: str
    occurred_at: datetime = Field(alias="occurredAt")


class PaymentCompletedEvent(BaseModel):
    """payment.completed routing key — published by Payment Service."""

    model_config = ConfigDict(populate_by_name=True)

    payment_id: str = Field(alias="paymentId")
    order_id: str = Field(alias="orderId")
    user_id: int = Field(alias="userId")
    amount: Decimal
    currency: str
    status: str
    occurred_at: datetime = Field(alias="occurredAt")


class PaymentFailedEvent(BaseModel):
    """payment.failed routing key — published by Payment Service."""

    model_config = ConfigDict(populate_by_name=True)

    payment_id: str = Field(alias="paymentId")
    order_id: str = Field(alias="orderId")
    user_id: int = Field(alias="userId")
    amount: Decimal
    currency: str
    status: str
    failure_reason: str = Field(alias="failureReason")
    occurred_at: datetime = Field(alias="occurredAt")


class PaymentCancelledEvent(BaseModel):
    """payment.cancelled routing key — published by Payment Service."""

    model_config = ConfigDict(populate_by_name=True)

    payment_id: str = Field(alias="paymentId")
    order_id: str = Field(alias="orderId")
    user_id: int = Field(alias="userId")
    amount: Decimal
    currency: str
    status: str
    occurred_at: datetime = Field(alias="occurredAt")
