package com.ecommerce.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code quantity} 1..100. A {@code quantity} of 0 is rejected (400); use DELETE to remove. */
public record SetQuantityRequest(@NotNull @Min(1) @Max(100) Integer quantity) {}
