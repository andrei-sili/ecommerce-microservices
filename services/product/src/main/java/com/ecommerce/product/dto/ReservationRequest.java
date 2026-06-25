package com.ecommerce.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Internal reserve request from Order: an order UUID plus the lines to reserve. */
public record ReservationRequest(
    @NotNull UUID orderId, @NotEmpty @Valid List<Item> items) {

  public record Item(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {}
}
