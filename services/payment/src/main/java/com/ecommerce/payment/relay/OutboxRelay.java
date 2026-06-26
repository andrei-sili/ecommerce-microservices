package com.ecommerce.payment.relay;

import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains unpublished outbox events to RabbitMQ with publisher confirms. At-least-once delivery: a
 * crash between publish and mark-published will re-publish on the next run; consumers dedup via
 * their inbox tables.
 *
 * <p>Uses {@code FOR UPDATE SKIP LOCKED} so multiple instances never process the same row.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  public static final String EXCHANGE = "ecommerce.events";

  private static final Map<String, String> ROUTING_KEYS =
      Map.of(
          "PaymentCompleted", "payment.completed",
          "PaymentFailed", "payment.failed",
          "PaymentCancelled", "payment.cancelled");

  private final OutboxEventRepository repository;
  private final RabbitTemplate rabbitTemplate;
  private final int batchSize;
  private final long confirmTimeoutMs;

  public OutboxRelay(
      OutboxEventRepository repository,
      RabbitTemplate rabbitTemplate,
      @Value("${outbox.relay.batch-size:50}") int batchSize,
      @Value("${outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
    this.repository = repository;
    this.rabbitTemplate = rabbitTemplate;
    this.batchSize = batchSize;
    this.confirmTimeoutMs = confirmTimeoutMs;
  }

  @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:1000}")
  @Transactional
  public void drain() {
    List<OutboxEvent> batch = repository.findUnpublishedWithLock(batchSize);
    if (batch.isEmpty()) {
      return;
    }
    try {
      // invoke() runs all sends on a single channel with publisher confirms enabled.
      rabbitTemplate.invoke(
          ops -> {
            for (OutboxEvent event : batch) {
              String routingKey =
                  ROUTING_KEYS.getOrDefault(event.getEventType(), event.getEventType());
              MessageProperties props = new MessageProperties();
              props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
              props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
              props.setMessageId(String.valueOf(event.getId()));
              props.setType(event.getEventType());
              props.setTimestamp(Date.from(event.getOccurredAt()));
              byte[] body = event.getPayload().getBytes(StandardCharsets.UTF_8);
              ops.send(
                  EXCHANGE, routingKey, new org.springframework.amqp.core.Message(body, props));
            }
            ops.waitForConfirmsOrDie(confirmTimeoutMs);
            return null;
          });

      // All confirms received: mark rows published in one bulk update.
      List<Long> ids = batch.stream().map(OutboxEvent::getId).toList();
      repository.markPublished(ids);
      log.debug("Relayed {} outbox events to {}", batch.size(), EXCHANGE);

    } catch (Exception e) {
      // Publish or confirm failed: transaction rolls back, rows stay unpublished for next run.
      log.warn("Outbox relay failed for batch of {} events: {}", batch.size(), e.getMessage());
      throw new RuntimeException("Outbox relay failed — will retry on next schedule", e);
    }
  }
}
