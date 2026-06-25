package com.ecommerce.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Reservation result. Carries the authoritative current prices so Order builds its line snapshot
 * from this single call (no separate price fetch).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationResponse(
    UUID orderId, String status, List<Item> items, String currency, BigDecimal subtotal) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(
      Long productId,
      String name,
      BigDecimal unitPrice,
      String currency,
      int quantity,
      BigDecimal lineTotal) {}
}
