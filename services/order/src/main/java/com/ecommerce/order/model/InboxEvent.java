package com.ecommerce.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** Dedup record for idempotent payment event consumption. */
@Entity
@Table(name = "inbox_events")
public class InboxEvent {

  @EmbeddedId private InboxEventId id;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  protected InboxEvent() {}

  public InboxEvent(String dedupKey, String eventType, Instant processedAt) {
    this.id = new InboxEventId(dedupKey, eventType);
    this.processedAt = processedAt;
  }

  public InboxEventId getId() {
    return id;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
