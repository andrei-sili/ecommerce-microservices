package com.ecommerce.order.exception;

import org.springframework.http.HttpStatus;

public class OrderNotCancellableException extends ApiException {

  public OrderNotCancellableException() {
    super(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE", "Order is not in a cancellable state");
  }
}
