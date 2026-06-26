package com.ecommerce.product.model;

public enum ReservationStatus {
  RESERVED,
  RELEASED,
  /** Payment confirmed: stock permanently decremented, reservation closed. */
  COMMITTED
}
