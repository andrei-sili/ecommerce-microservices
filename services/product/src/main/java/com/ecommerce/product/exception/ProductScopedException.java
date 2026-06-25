package com.ecommerce.product.exception;

import org.springframework.http.HttpStatus;

/**
 * An {@link ApiException} that names the offending product in the error body (field {@code
 * product_id}), per the reservation contract. Used for {@code INSUFFICIENT_STOCK} (409) and {@code
 * PRODUCT_NOT_FOUND} (422) on the internal reservation endpoints.
 */
public class ProductScopedException extends ApiException {

  private final Long productId;

  public ProductScopedException(HttpStatus status, String code, String message, Long productId) {
    super(status, code, message);
    this.productId = productId;
  }

  public Long getProductId() {
    return productId;
  }
}
