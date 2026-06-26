package com.ecommerce.payment.dto;

import java.math.BigDecimal;

/** Parsed body of a gateway webhook call. Used only after signature verification. */
public class WebhookEventRequest {

  /** Unique gateway event id — used for idempotency dedup. */
  private String eventId;

  /** Gateway event type: payment_succeeded / payment_failed / payment_canceled. */
  private String eventType;

  /** The gateway's own payment identifier (maps to payments.gateway_payment_id). */
  private String gatewayPaymentId;

  private BigDecimal amount;
  private String currency;
  private String failureReason;

  public String getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getGatewayPaymentId() {
    return gatewayPaymentId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getFailureReason() {
    return failureReason;
  }
}
