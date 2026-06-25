package com.ecommerce.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload for the {@code OrderPlaced} event (contract: camelCase, matches {@code UserRegistered}).
 */
public record OrderPlacedPayload(
    UUID orderId,
    Long userId,
    List<Item> items,
    BigDecimal total,
    String currency,
    Instant occurredAt) {

  public record Item(Long productId, int quantity, BigDecimal unitPrice) {}
}
