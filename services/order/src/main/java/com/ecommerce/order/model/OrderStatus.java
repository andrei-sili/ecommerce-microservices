package com.ecommerce.order.model;

/**
 * Order lifecycle states. Terminal states: {@code PAID}, {@code PAYMENT_FAILED}, {@code CANCELLED}.
 */
public enum OrderStatus {
  PENDING,
  PAID,
  PAYMENT_FAILED,
  CANCELLED
}
