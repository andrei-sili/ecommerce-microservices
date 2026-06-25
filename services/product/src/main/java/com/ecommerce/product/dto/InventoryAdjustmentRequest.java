package com.ecommerce.product.dto;

import jakarta.validation.constraints.NotNull;

public record InventoryAdjustmentRequest(@NotNull Integer delta) {}
