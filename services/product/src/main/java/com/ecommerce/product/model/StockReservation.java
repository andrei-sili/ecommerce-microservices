package com.ecommerce.product.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One reserved line of an order's stock reservation. {@code products.reserved_quantity} is the
 * running sum of {@code RESERVED} rows; a {@code RELEASED} row no longer counts toward it. {@code
 * (order_id, product_id)} is unique so a replayed reservation is idempotent.
 *
 * <p>Wave 3 adds {@code expires_at}: the sweeper releases RESERVED rows past this instant, and
 * commit rejects RELEASED/expired rows (409 RESERVATION_NOT_ACTIVE).
 */
@Entity
@Table(name = "stock_reservations")
public class StockReservation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(nullable = false)
  private int quantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReservationStatus status;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public StockReservation() {}

  public StockReservation(
      UUID orderId, Long productId, int quantity, ReservationStatus status, Instant expiresAt) {
    this.orderId = orderId;
    this.productId = productId;
    this.quantity = quantity;
    this.status = status;
    this.expiresAt = expiresAt;
  }

  public Long getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public Long getProductId() {
    return productId;
  }

  public int getQuantity() {
    return quantity;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public void setStatus(ReservationStatus status) {
    this.status = status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
