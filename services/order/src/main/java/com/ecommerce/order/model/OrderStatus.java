package com.ecommerce.order.model;

/**
 * Wave 2 lifecycle. {@code PAID} arrives in Wave 3 when Order consumes {@code PaymentCompleted}.
 */
public enum OrderStatus {
  PENDING,
  CANCELLED
}
