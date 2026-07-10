package com.ecommerce.payment.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Subset of the Order Service response used by Payment. Most fields are snake_case on the wire
 * (Boot's SNAKE_CASE ObjectMapper maps {@code user_id → userId}, etc.), but the Order object's
 * identifier serialises as {@code id} (record component {@code UUID id}) — snake_case leaves {@code
 * id} as {@code id} — so {@code orderId} is bound explicitly via {@link JsonProperty} to keep the
 * stable {@code .orderId()} accessor while reading the real wire key.
 */
public record OrderView(
    @JsonProperty("id") UUID orderId,
    Long userId,
    String status,
    BigDecimal total,
    String currency) {}
