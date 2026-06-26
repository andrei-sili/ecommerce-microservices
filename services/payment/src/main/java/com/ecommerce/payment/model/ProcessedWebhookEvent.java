package com.ecommerce.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Idempotency dedup for gateway webhook events. */
@Entity
@Table(name = "processed_webhook_events")
public class ProcessedWebhookEvent {

  @Id
  @Column(name = "gateway_event_id", length = 100)
  private String gatewayEventId;

  @Column(name = "payment_id", nullable = false)
  private UUID paymentId;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  protected ProcessedWebhookEvent() {}

  public ProcessedWebhookEvent(String gatewayEventId, UUID paymentId) {
    this.gatewayEventId = gatewayEventId;
    this.paymentId = paymentId;
  }

  @PrePersist
  void onCreate() {
    this.receivedAt = Instant.now();
  }

  public String getGatewayEventId() {
    return gatewayEventId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
