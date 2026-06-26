package com.ecommerce.payment.event;

import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Records payment domain events in the transactional outbox. The payload uses camelCase (event
 * contract) via a dedicated mapper, independent of the API's snake_case convention. Must be called
 * inside the same transaction as the payment state change it records.
 */
@Service
public class OutboxService {

  private static final String AGGREGATE_TYPE = "Payment";

  private final OutboxEventRepository repository;

  // Dedicated mapper: event payloads are camelCase (api_contracts.md catalog).
  private final JsonMapper mapper =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  public OutboxService(OutboxEventRepository repository) {
    this.repository = repository;
  }

  public void recordPaymentCompleted(Payment payment, Instant occurredAt) {
    PaymentCompletedPayload payload =
        new PaymentCompletedPayload(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getStatus().name(),
            occurredAt);
    save(payment, "PaymentCompleted", payload, occurredAt);
  }

  public void recordPaymentFailed(Payment payment, Instant occurredAt) {
    PaymentFailedPayload payload =
        new PaymentFailedPayload(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getStatus().name(),
            payment.getFailureReason(),
            occurredAt);
    save(payment, "PaymentFailed", payload, occurredAt);
  }

  public void recordPaymentCancelled(Payment payment, Instant occurredAt) {
    PaymentCancelledPayload payload =
        new PaymentCancelledPayload(
            payment.getId(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getStatus().name(),
            occurredAt);
    save(payment, "PaymentCancelled", payload, occurredAt);
  }

  private void save(Payment payment, String eventType, Object payload, Instant occurredAt) {
    repository.save(
        new OutboxEvent(
            AGGREGATE_TYPE,
            payment.getId().toString(),
            eventType,
            serialize(payload),
            occurredAt));
  }

  private String serialize(Object payload) {
    try {
      return mapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize outbox payload", e);
    }
  }
}
