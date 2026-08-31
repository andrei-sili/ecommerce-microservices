package com.ecommerce.payment.relay;

import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
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
 * Drains unpublished {@code outbox_events} rows to RabbitMQ with per-publish publisher confirms.
 * Uses {@code FOR UPDATE SKIP LOCKED} so multiple instances never process the same row. At-least-
 * once: a crash between publish and {@code published_at} update re-publishes on the next run;
 * consumers dedup on the payload's stable domain key ({@code paymentId}).
 *
 * <p><strong>Broker confirm != routable.</strong> A confirmed publish only proves the broker
 * accepted the message into the exchange — a topic exchange silently discards a message with no
 * matching binding, yet still {@code ack}s it. So a row is marked published ONLY when its publish
 * was confirmed (ack) AND NOT returned as unroutable (basic.return) and NOT nack'd. A returned/
 * nack'd/timed-out row stays {@code published_at IS NULL} and is retried on the next run — until
 * the consumer's durable queue exists. This closes the empty-broker / cold-start window that
 * otherwise silently loses {@code PaymentCompleted}/{@code PaymentFailed}/{@code PaymentCancelled}
 * notifications.
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

    List<Pending> pending = new ArrayList<>();
    for (OutboxEvent event : batch) {
      String routingKey = ROUTING_KEYS.get(event.getEventType());
      if (routingKey == null) {
        log.warn(
            "No routing key for outbox event type '{}' (id={}); skipping",
            event.getEventType(),
            event.getId());
        continue;
      }
      // Correlate each publish to its outbox row id so the confirm/return maps back to THIS row
      // (never mis-attributed to another row in the same batch).
      CorrelationData correlation = new CorrelationData(String.valueOf(event.getId()));
      rabbitTemplate.send(EXCHANGE, routingKey, buildMessage(event), correlation);
      pending.add(new Pending(event, routingKey, correlation));
    }

    // One batch-wide deadline bounds how long row locks are held while awaiting confirms: a hung
    // broker cannot block for confirmTimeoutMs * batchSize (Order review finding #1).
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
    for (Pending p : pending) {
      if (confirmedRoutable(p, deadlineNanos)) {
        p.event().markPublished(Instant.now());
        log.debug(
            "Relayed outbox event id={} type={} routingKey={}",
            p.event().getId(),
            p.event().getEventType(),
            p.routingKey());
      }
      // Otherwise the row stays published_at IS NULL and is retried on the next scheduled run.
    }
    // Transaction commits here: published_at is flushed (dirty checking) only for rows whose
    // publish was confirmed AND routable.
  }

  /**
   * A row may be marked published ONLY when the broker CONFIRMED the publish (ack) AND the message
   * was NOT returned as unroutable (basic.return) and NOT nack'd. Waits for the confirm up to the
   * shared batch deadline; a timeout/nack/return all leave the row unpublished for the next run.
   */
  private boolean confirmedRoutable(Pending p, long deadlineNanos) {
    try {
      long remainingMs =
          Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
      CorrelationData.Confirm confirm =
          p.correlation().getFuture().get(remainingMs, TimeUnit.MILLISECONDS);
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
          "No publisher confirm for outbox event id={} within the batch deadline; will retry: {}",
          p.event().getId(),
          e.getMessage());
      return false;
    }
  }

  private Message buildMessage(OutboxEvent event) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(String.valueOf(event.getId()));
    props.setType(event.getEventType());
    props.setTimestamp(Date.from(event.getOccurredAt()));
    byte[] body = event.getPayload().getBytes(StandardCharsets.UTF_8);
    return new Message(body, props);
  }

  /** One in-flight publish awaiting its broker confirm. */
  private record Pending(OutboxEvent event, String routingKey, CorrelationData correlation) {}
}
