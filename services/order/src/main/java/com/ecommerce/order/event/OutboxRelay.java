package com.ecommerce.order.event;

import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled drainer that relays unpublished {@code outbox_events} rows to RabbitMQ. Runs every ~1s
 * (configurable); uses {@code FOR UPDATE SKIP LOCKED} so multiple instances never double-publish
 * the same row. At-least-once: if the process crashes between publish and {@code published_at}
 * update, the next run re-publishes; consumers dedup on the payload's stable domain key.
 *
 * <p><strong>Broker confirm != routable.</strong> A confirmed publish only proves the broker
 * accepted the message into the exchange — a topic exchange silently discards a message with no
 * matching binding, yet still {@code ack}s it. So a row is marked published ONLY when its publish
 * was confirmed (ack) AND NOT returned as unroutable (and not nack'd). A returned/nack'd row stays
 * {@code published_at IS NULL} and is retried on the next run — until the consumer's durable queue
 * exists. This closes the empty-broker / cold-start window that otherwise loses events.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final String EXCHANGE = "ecommerce.events";
  private static final int BATCH_SIZE = 50;

  private final OutboxEventRepository outboxEventRepository;
  private final RabbitTemplate rabbitTemplate;
  private final long confirmTimeoutMs;

  public OutboxRelay(
      OutboxEventRepository outboxEventRepository,
      RabbitTemplate rabbitTemplate,
      @Value("${app.outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
    this.outboxEventRepository = outboxEventRepository;
    this.rabbitTemplate = rabbitTemplate;
    this.confirmTimeoutMs = confirmTimeoutMs;
  }

  @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms:1000}")
  @Transactional
  public void drain() {
    List<OutboxEvent> batch = outboxEventRepository.findUnpublishedBatch(BATCH_SIZE);
    List<Pending> pending = new ArrayList<>();
    for (OutboxEvent event : batch) {
      String routingKey = routingKeyFor(event.getEventType());
      if (routingKey == null) {
        log.warn(
            "No routing key for event type '{}' (id={}), skipping",
            event.getEventType(),
            event.getId());
        continue;
      }
      Message amqpMessage = buildAmqpMessage(event);
      // Correlate each publish to its outbox row id so the confirm/return maps back to the row.
      CorrelationData correlation = new CorrelationData(String.valueOf(event.getId()));
      rabbitTemplate.send(EXCHANGE, routingKey, amqpMessage, correlation);
      pending.add(new Pending(event, routingKey, correlation));
    }

    for (Pending p : pending) {
      if (confirmedRoutable(p)) {
        p.event().setPublishedAt(Instant.now());
        log.debug(
            "Relayed outbox event id={} type={} routingKey={}",
            p.event().getId(),
            p.event().getEventType(),
            p.routingKey());
      }
      // Otherwise the row stays published_at IS NULL and is retried on the next scheduled run.
    }
    // Transaction commits here: published_at is persisted only for confirmed, routable events.
  }

  /**
   * A row may be marked published ONLY when the broker CONFIRMED the publish (ack) AND the message
   * was NOT returned as unroutable (basic.return) and NOT nack'd. Waits for the confirm with a
   * bounded timeout; a timeout/nack/return all leave the row unpublished for the next run.
   */
  private boolean confirmedRoutable(Pending p) {
    try {
      CorrelationData.Confirm confirm =
          p.correlation().getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
      boolean returned = p.correlation().getReturned() != null;
      if (confirm.ack() && !returned) {
        return true;
      }
      log.warn(
          "Outbox event id={} not routable/confirmed (ack={}, returned={}); will retry next run",
          p.event().getId(),
          confirm.ack(),
          returned);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
          "Interrupted awaiting confirm for outbox event id={}; will retry", p.event().getId());
      return false;
    } catch (ExecutionException | TimeoutException e) {
      log.warn(
          "No publisher confirm for outbox event id={} within {}ms; will retry: {}",
          p.event().getId(),
          confirmTimeoutMs,
          e.getMessage());
      return false;
    }
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

  /** One in-flight publish awaiting its broker confirm. */
  private record Pending(OutboxEvent event, String routingKey, CorrelationData correlation) {}
}
