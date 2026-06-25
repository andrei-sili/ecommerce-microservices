package com.ecommerce.product.dto;

import com.ecommerce.product.model.Product;

public record InventoryResponse(
    Long productId, Integer stockQuantity, Integer reservedQuantity, Boolean available) {

  public static InventoryResponse from(Product product) {
    return new InventoryResponse(
        product.getId(),
        product.getStockQuantity(),
        product.getReservedQuantity(),
        product.isAvailable());
  }
}
