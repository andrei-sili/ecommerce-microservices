package com.ecommerce.product.dto;

import com.ecommerce.product.model.Product;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
    Long id,
    String sku,
    String name,
    String description,
    BigDecimal price,
    String currency,
    CategoryResponse category,
    Integer stockQuantity,
    Boolean available,
    Instant createdAt,
    Instant updatedAt) {

  public static ProductResponse from(Product product) {
    return new ProductResponse(
        product.getId(),
        product.getSku(),
        product.getName(),
        product.getDescription(),
        product.getPrice(),
        product.getCurrency(),
        CategoryResponse.from(product.getCategory()),
        product.getStockQuantity(),
        product.isAvailable(),
        product.getCreatedAt(),
        product.getUpdatedAt());
  }
}
