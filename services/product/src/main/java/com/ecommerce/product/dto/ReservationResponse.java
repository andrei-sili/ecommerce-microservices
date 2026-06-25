package com.ecommerce.product.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative reserve result. Carries the current unit prices/currency/name so Order builds its
 * line snapshot from this one call (no separate price fetch). {@code subtotal} is the sum of the
 * line totals; {@code currency} is the single shared currency of all lines.
 */
public record ReservationResponse(
    UUID orderId, String status, List<Line> items, String currency, BigDecimal subtotal) {

  public record Line(
      Long productId,
      String name,
      BigDecimal unitPrice,
      String currency,
      Integer quantity,
      BigDecimal lineTotal) {}
}
