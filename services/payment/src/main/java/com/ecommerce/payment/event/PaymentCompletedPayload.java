package com.ecommerce.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** camelCase — event payload contract from api_contracts.md. */
public record PaymentCompletedPayload(
    UUID paymentId,
    UUID orderId,
    Long userId,
    BigDecimal amount,
    String currency,
    String status,
    Instant occurredAt) {}
