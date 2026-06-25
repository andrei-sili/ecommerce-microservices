package com.ecommerce.user.event;

import com.ecommerce.user.model.OutboxEvent;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Records domain events in the transactional outbox. Wave 1 only persists them; the RabbitMQ relay
 * is added in Wave 3. Must be called inside the same transaction as the state change it records.
 */
@Service
public class OutboxService {

  private static final String AGGREGATE_TYPE = "User";

  private final OutboxEventRepository repository;
  // Dedicated mapper: the event contract uses camelCase field names (userId, occurredAt),
  // independent of the API's snake_case JSON convention.
  private final JsonMapper mapper =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  public OutboxService(OutboxEventRepository repository) {
    this.repository = repository;
  }

  public void recordUserRegistered(Long userId, String email, Instant occurredAt) {
    UserRegisteredPayload payload = new UserRegisteredPayload(userId, email, occurredAt);
    repository.save(
        new OutboxEvent(
            AGGREGATE_TYPE,
            String.valueOf(userId),
            "UserRegistered",
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
