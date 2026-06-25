package com.ecommerce.order.client;

import java.util.List;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/inventory/reservations}. Serialized snake_case by global Jackson.
 */
public record ReservationRequest(UUID orderId, List<Line> items) {

  public record Line(Long productId, int quantity) {}
}
