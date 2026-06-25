package com.ecommerce.order.exception;

import org.springframework.http.HttpStatus;

/**
 * Returned both for a missing order and for one owned by another user — the message never reveals
 * which, so a caller cannot probe for the existence of other users' orders.
 */
public class OrderNotFoundException extends ApiException {

  public OrderNotFoundException() {
    super(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
  }
}
