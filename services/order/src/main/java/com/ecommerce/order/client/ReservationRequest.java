package com.ecommerce.order.client;

import java.util.List;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/inventory/reservations}. Serialized snake_case ({@code order_id},
 * {@code product_id}) by the snake_case ObjectMapper that the client beans inherit from Boot's
 * RestClient.Builder (see {@code ClientsConfig}).
 */
public record ReservationRequest(UUID orderId, List<Line> items) {

  public record Line(Long productId, int quantity) {}
}
