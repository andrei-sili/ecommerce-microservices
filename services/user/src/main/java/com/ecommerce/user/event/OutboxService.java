package com.ecommerce.user.event;

import com.ecommerce.user.model.OutboxEvent;
import com.ecommerce.user.repository.OutboxEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

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
  //
  // The disable() stays EXPLICIT even though Jackson 3 already defaults it off (contract B7): this
  // mapper exists precisely to be independent of framework defaults, and leaning on an upstream
  // default for a contract-binding representation is not a pin. JavaTimeModule is gone because
  // Jackson 3 registers java.time support itself; keeping it would be a second registration of the
  // same serializers, not a safety net.
  private final JsonMapper mapper =
      JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

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
