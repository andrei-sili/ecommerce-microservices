package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.support.AbstractBrokerIntegrationTest;
import com.ecommerce.payment.support.WireEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * C10 / AC-5.9: a production-size batch drains in ONE pass, and the shared deadline is bounded.
 *
 * <p><b>The asymmetry this exists to bound.</b> payment computes ONE deadline for the whole batch
 * ({@code OutboxRelay:96}) and consumes it per row ({@code :119-122}); order gives every row its
 * own full {@code confirmTimeoutMs}. Nothing else in the fleet records that difference. It is not
 * academic: if confirm latency rises, payment's TAIL rows time out <b>after already being sent</b>,
 * stay {@code published_at IS NULL}, get re-published on the next tick, and degrade quietly into
 * unbounded duplicates plus outbox growth. Consumers dedup on {@code paymentId}, so nothing alarms.
 *
 * <p><b>Ten DISTINCT message ids, not a count of ten.</b> A single message redelivered ten times
 * satisfies a naive count while hiding a batch that never drained — so the ids are collected into a
 * Set and compared to the row ids, which also proves the {@code messageId} property still carries
 * the row id a consumer would dedup on.
 *
 * <p><b>The wall-clock bound is read from configuration, not written as a literal.</b> The
 * assertion is {@code < confirmTimeoutMs / 2}: with a healthy broker every confirm arrives in
 * milliseconds, so consuming even half the shared deadline for ten rows means the batch is already
 * eating the budget its tail depends on. Deriving the bound from {@code
 * outbox.relay.confirm-timeout-ms} keeps the two in step if the timeout is ever retuned.
 */
class OutboxRelayBatchIntegrationTest extends AbstractBrokerIntegrationTest {

  private static final String COMPLETED_QUEUE = "test.payment-completed";
  private static final String ORDER_ID = "9f1c2e7a-1111-2222-3333-444455556666";
  private static final int BATCH = 10;

  /** Fixed: AMQP timestamps carry second resolution, so Instant.now() cannot round-trip. */
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-26T10:00:00Z");

  @Autowired private OutboxRelay outboxRelay;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;

  @Value("${outbox.relay.confirm-timeout-ms:5000}")
  private long confirmTimeoutMs;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
    resetSeamTopology(rabbitAdmin);
  }

  @Test
  void tenRoutableRows_drainInOnePass_tenDistinctDeliveries_withinHalfTheSharedDeadline() {
    Queue queue = QueueBuilder.durable(COMPLETED_QUEUE).build();
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue)
            .to(new TopicExchange(EXCHANGE, true, false))
            .with("payment.completed"));

    List<OutboxEvent> rows = new ArrayList<>();
    for (int i = 0; i < BATCH; i++) {
      rows.add(outboxEventRepository.save(paymentCompletedRow(UUID.randomUUID().toString())));
    }

    long startNanos = System.nanoTime();
    outboxRelay.drain();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    for (OutboxEvent row : rows) {
      assertThat(outboxEventRepository.findById(row.getId()).orElseThrow().getPublishedAt())
          .as(
              "row %s must be published in the SAME pass — no tail left for the next tick",
              row.getId())
          .isNotNull();
    }

    Set<String> deliveredIds = new LinkedHashSet<>();
    for (int i = 0; i < BATCH; i++) {
      Message delivered = rabbitTemplate.receive(COMPLETED_QUEUE, 5000);
      assertThat(delivered).as("delivery %s of %s", i + 1, BATCH).isNotNull();
      String messageId = delivered.getMessageProperties().getMessageId();
      deliveredIds.add(messageId);
      WireEnvelope.assertMatches(
          delivered, outboxEventRepository.findById(Long.valueOf(messageId)).orElseThrow());
    }

    assertThat(deliveredIds)
        .as("ten DISTINCT deliveries — one redelivered message would satisfy a count of ten")
        .containsExactlyInAnyOrderElementsOf(
            rows.stream().map(r -> String.valueOf(r.getId())).toList());
    assertThat(rabbitTemplate.receive(COMPLETED_QUEUE, 500))
        .as("and nothing beyond the batch")
        .isNull();

    assertThat(elapsed)
        .as(
            "one pass over %s rows must not consume half the deadline the whole batch shares"
                + " (confirm-timeout-ms=%s); measured %s ms",
            BATCH, confirmTimeoutMs, elapsed.toMillis())
        .isLessThan(Duration.ofMillis(confirmTimeoutMs / 2));
  }

  private OutboxEvent paymentCompletedRow(String paymentId) {
    String payload =
        ("{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":7,\"amount\":39.98,"
                + "\"currency\":\"EUR\",\"status\":\"SUCCEEDED\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}")
            .formatted(paymentId, ORDER_ID);
    return new OutboxEvent("Payment", paymentId, "PaymentCompleted", payload, OCCURRED_AT);
  }
}
