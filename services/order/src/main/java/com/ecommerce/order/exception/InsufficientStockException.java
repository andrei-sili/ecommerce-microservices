package com.ecommerce.order.exception;

import org.springframework.http.HttpStatus;

/** Stock cannot cover the order. Carries the first short product so the client can react. */
public class InsufficientStockException extends ApiException {

  private final Long productId;

  public InsufficientStockException(Long productId, String message) {
    super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", message);
    this.productId = productId;
  }

  public Long getProductId() {
    return productId;
  }
}
