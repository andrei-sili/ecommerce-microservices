package com.ecommerce.payment.dto;

import com.ecommerce.payment.model.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public payment representation. Never includes the gateway token, webhook secret, or any card
 * data.
 */
public class PaymentResponse {

  private UUID id;
  private UUID orderId;
  private Long userId;
  private BigDecimal amount;
  private String currency;
  private String status;
  private String gateway;
  private String failureReason;
  private Instant createdAt;
  private Instant updatedAt;

  public static PaymentResponse from(Payment p) {
    PaymentResponse r = new PaymentResponse();
    r.id = p.getId();
    r.orderId = p.getOrderId();
    r.userId = p.getUserId();
    r.amount = p.getAmount();
    r.currency = p.getCurrency();
    r.status = p.getStatus().name();
    r.gateway = p.getGateway();
    r.failureReason = p.getFailureReason();
    r.createdAt = p.getCreatedAt();
    r.updatedAt = p.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public Long getUserId() {
    return userId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getStatus() {
    return status;
  }

  public String getGateway() {
    return gateway;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
