package com.ecommerce.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddItemRequest(
    @NotNull Long productId, @NotNull @Min(1) @Max(100) Integer quantity) {}
