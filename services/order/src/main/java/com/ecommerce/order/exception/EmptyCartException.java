package com.ecommerce.order.exception;

import org.springframework.http.HttpStatus;

public class EmptyCartException extends ApiException {

  public EmptyCartException() {
    super(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_CART", "Cannot place an order from an empty cart");
  }
}
