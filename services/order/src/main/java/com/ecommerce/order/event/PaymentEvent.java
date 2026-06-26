package com.ecommerce.order.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payment event payload (camelCase per contract). Covers PaymentCompleted, PaymentFailed, and
 * PaymentCancelled — all share the same fields; {@code failureReason} is absent on non-failure
 * events. Deserialized with a dedicated camelCase mapper (see {@link PaymentEventConsumer}),
 * independently of the REST snake_case ObjectMapper.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(
    String paymentId,
    String orderId,
    Long userId,
    BigDecimal amount,
    String currency,
    String status,
    String failureReason,
    Instant occurredAt) {}
