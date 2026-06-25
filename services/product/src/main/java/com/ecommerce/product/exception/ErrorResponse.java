package com.ecommerce.product.exception;

import java.time.Instant;

/** Standard error body shared across all services (see api-design.md). Never leaks internals. */
public record ErrorResponse(String error, String message, Instant timestamp, String path) {

  public static ErrorResponse of(String error, String message, String path) {
    return new ErrorResponse(error, message, Instant.now(), path);
  }
}
