package com.ecommerce.order.event;

import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled drainer that relays unpublished {@code outbox_events} rows to RabbitMQ. Runs every ~1s
 * (configurable); uses {@code FOR UPDATE SKIP LOCKED} so multiple instances never double-publish
 * the same row. At-least-once: if the process crashes between publish and {@code published_at}
 * update, the next run re-publishes; consumers dedup on the payload's stable domain key.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final String EXCHANGE = "ecommerce.events";
  private static final int BATCH_SIZE = 50;

  private final OutboxEventRepository outboxEventRepository;
  private final RabbitTemplate rabbitTemplate;

  public OutboxRelay(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate) {
    this.outboxEventRepository = outboxEventRepository;
    this.rabbitTemplate = rabbitTemplate;
  }

  @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms:1000}")
  @Transactional
  public void drain() {
    List<OutboxEvent> batch = outboxEventRepository.findUnpublishedBatch(BATCH_SIZE);
    for (OutboxEvent event : batch) {
      String routingKey = routingKeyFor(event.getEventType());
      if (routingKey == null) {
        log.warn("No routing key for event type '{}' (id={}), skipping", event.getEventType(), event.getId());
        continue;
      }
      try {
        Message amqpMessage = buildAmqpMessage(event);
        rabbitTemplate.convertAndSend(EXCHANGE, routingKey, amqpMessage);
        event.setPublishedAt(Instant.now());
        log.debug("Relayed outbox event id={} type={} routingKey={}", event.getId(), event.getEventType(), routingKey);
      } catch (AmqpException | RuntimeException e) {
        log.error(
            "Failed to publish outbox event id={}: {}, stopping batch", event.getId(), e.getMessage());
        // Break so the transaction commits for events already published in this batch.
        // The failed event stays unpublished and will be retried on the next run.
        break;
      }
    }
    // Transaction commits here: published_at is persisted for events that were relayed.
  }

  /** Maps an event type to its RabbitMQ routing key per the contract. */
  private String routingKeyFor(String eventType) {
    return switch (eventType) {
      case "OrderPlaced" -> "order.placed";
      default -> null;
    };
  }

  private Message buildAmqpMessage(OutboxEvent event) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(String.valueOf(event.getId()));
    props.setType(event.getEventType());
    if (event.getOccurredAt() != null) {
      props.setTimestamp(Date.from(event.getOccurredAt()));
    }
    byte[] body = event.getPayload().getBytes(StandardCharsets.UTF_8);
    return new Message(body, props);
  }
}
