package com.ecommerce.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit entry. Never stores card data (PAN/CVV/secrets). */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "payment_id", nullable = false)
  private UUID paymentId;

  /** AUTHORIZE / CAPTURE / WEBHOOK / STATUS_CHANGE */
  @Column(nullable = false, length = 30)
  private String type;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(length = 3)
  private String currency;

  @Column(name = "gateway_event_id", length = 100)
  private String gatewayEventId;

  /** Machine-safe description — must never contain PAN, CVV, or secrets. */
  @Column(length = 255)
  private String detail;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PaymentTransaction() {}

  public PaymentTransaction(
      UUID paymentId,
      String type,
      String status,
      BigDecimal amount,
      String currency,
      String gatewayEventId,
      String detail) {
    this.paymentId = paymentId;
    this.type = type;
    this.status = status;
    this.amount = amount;
    this.currency = currency;
    this.gatewayEventId = gatewayEventId;
    this.detail = detail;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public String getType() {
    return type;
  }

  public String getStatus() {
    return status;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getGatewayEventId() {
    return gatewayEventId;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
