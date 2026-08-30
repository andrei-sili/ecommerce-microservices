package com.ecommerce.order.event;

import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records domain events in the transactional outbox. Wave 2 only persists them; the RabbitMQ relay
 * is added in Wave 3. Must be called inside the same transaction as the order insert it records.
 */
@Service
public class OutboxService {

  private static final String AGGREGATE_TYPE = "Order";

  private final OutboxEventRepository repository;
  // Dedicated mapper: the event contract uses camelCase field names (orderId, occurredAt),
  // independent of the API's snake_case JSON convention.
  private final JsonMapper mapper =
      JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

  public OutboxService(OutboxEventRepository repository) {
    this.repository = repository;
  }

  public void recordOrderPlaced(OrderEntity order, Instant occurredAt) {
    List<OrderPlacedPayload.Item> items =
        order.getItems().stream()
            .map(
                (OrderItem it) ->
                    new OrderPlacedPayload.Item(
                        it.getProductId(), it.getQuantity(), it.getUnitPrice()))
            .toList();
    OrderPlacedPayload payload =
        new OrderPlacedPayload(
            order.getId(),
            order.getUserId(),
            items,
            order.getTotal(),
            order.getCurrency(),
            occurredAt);
    repository.save(
        new OutboxEvent(
            AGGREGATE_TYPE,
            order.getId().toString(),
            "OrderPlaced",
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
