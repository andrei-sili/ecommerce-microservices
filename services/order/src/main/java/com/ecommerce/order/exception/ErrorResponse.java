package com.ecommerce.order.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** Standard error body shared across all services (see api-design.md). Never leaks internals. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String error, String message, Instant timestamp, String path, Long productId) {

  public static ErrorResponse of(String error, String message, String path) {
    return new ErrorResponse(error, message, Instant.now(), path, null);
  }

  public static ErrorResponse withProduct(
      String error, String message, String path, Long productId) {
    return new ErrorResponse(error, message, Instant.now(), path, productId);
  }
}
