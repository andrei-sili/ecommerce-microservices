package com.ecommerce.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite PK for {@link InboxEvent}: (dedup_key, event_type). */
@Embeddable
public class InboxEventId implements Serializable {

  @Column(name = "dedup_key", nullable = false, length = 200)
  private String dedupKey;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  protected InboxEventId() {}

  public InboxEventId(String dedupKey, String eventType) {
    this.dedupKey = dedupKey;
    this.eventType = eventType;
  }

  public String getDedupKey() {
    return dedupKey;
  }

  public String getEventType() {
    return eventType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof InboxEventId other)) return false;
    return Objects.equals(dedupKey, other.dedupKey) && Objects.equals(eventType, other.eventType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(dedupKey, eventType);
  }
}
