package com.ecommerce.product.exception;

import java.time.Instant;

/**
 * Standard error body extended with the offending {@code product_id} for reservation failures
 * (contract: {@code INSUFFICIENT_STOCK} / {@code PRODUCT_NOT_FOUND}). Keeps the same shape as {@link
 * ErrorResponse}, adding one machine-readable field; never leaks internals.
 */
public record ProductScopedErrorResponse(
    String error, String message, Instant timestamp, String path, Long productId) {

  public static ProductScopedErrorResponse of(
      String error, String message, String path, Long productId) {
    return new ProductScopedErrorResponse(error, message, Instant.now(), path, productId);
  }
}
