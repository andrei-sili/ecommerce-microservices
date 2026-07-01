package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.support.AbstractBrokerIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

/**
 * Strict 2-key mis-correlation seam test against a REAL RabbitMQ (Testcontainers), zero broker
 * mocks. Payment emits three routing keys, so — unlike Order (one key) — a single {@code drain()}
 * can carry one ROUTABLE key and one RETURNED-unroutable key SIMULTANEOUSLY, using TEST-ONLY
 * bindings (no production routing change).
 *
 * <p>It binds only {@code payment.completed} and leaves {@code payment.failed} unbound, then drains
 * a batch of one {@code PaymentCompleted} (routable) + one {@code PaymentFailed} (unroutable) in
 * the SAME pass. This proves the per-{@code CorrelationData} attribution is correct: the returned
 * row's {@code basic.return} is NOT mis-assigned to the routable row's {@code ack} (an ack-only
 * relay would mark both published and drop the failed notification).
 */
class OutboxRelayMisCorrelationIntegrationTest extends AbstractBrokerIntegrationTest {

  private static final String COMPLETED_QUEUE = "test.payment-completed";
  private static final String FAILED_QUEUE = "test.payment-failed";
  private static final String COMPLETED_PAYMENT_ID = "aaaa1111-1111-1111-1111-111111111111";
  private static final String FAILED_PAYMENT_ID = "bbbb2222-2222-2222-2222-222222222222";
  private static final String ORDER_ID = "9f1c2e7a-1111-2222-3333-444455556666";

  @Autowired private OutboxRelay outboxRelay;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
    resetSeamTopology(rabbitAdmin);
  }

  @Test
  void mixedBatch_routableMarked_returnedStaysNull_perRowAttribution() {
    // Test-only topology: bind ONLY payment.completed; leave payment.failed UNBOUND.
    bindQueue(COMPLETED_QUEUE, "payment.completed");

    // Inserted in id order: PaymentCompleted (routable) first, PaymentFailed (unroutable) second.
    OutboxEvent completed = outboxEventRepository.save(paymentCompletedRow(COMPLETED_PAYMENT_ID));
    OutboxEvent failed = outboxEventRepository.save(paymentFailedRow(FAILED_PAYMENT_ID));

    // ---- ONE drain: completed is routed+confirmed; failed is returned unroutable — SAME pass.
    // ----
    outboxRelay.drain();

    assertThat(outboxEventRepository.findById(completed.getId()).orElseThrow().getPublishedAt())
        .as("routable PaymentCompleted row must be marked published")
        .isNotNull();
    assertThat(outboxEventRepository.findById(failed.getId()).orElseThrow().getPublishedAt())
        .as("returned-unroutable PaymentFailed must stay NULL — not mis-attributed to the ack")
        .isNull();

    // Only the routable event reached a queue; the returned one is nowhere.
    Message delivered = rabbitTemplate.receive(COMPLETED_QUEUE, 5000);
    assertThat(delivered).as("routable event delivered").isNotNull();
    assertThat(delivered.getMessageProperties().getType()).isEqualTo("PaymentCompleted");
    assertThat(new String(delivered.getBody(), StandardCharsets.UTF_8))
        .contains(COMPLETED_PAYMENT_ID);
    assertThat(rabbitTemplate.receive(COMPLETED_QUEUE, 500))
        .as("only the routable event is delivered to the bound queue")
        .isNull();

    // The returned row is retried on the next tick: bind payment.failed, drain again → now
    // published + delivered (zero loss; the earlier return did not consume or drop the row).
    bindQueue(FAILED_QUEUE, "payment.failed");
    outboxRelay.drain();

    assertThat(outboxEventRepository.findById(failed.getId()).orElseThrow().getPublishedAt())
        .as("previously-returned row is retried and published once its queue exists")
        .isNotNull();
    Message failedDelivered = rabbitTemplate.receive(FAILED_QUEUE, 5000);
    assertThat(failedDelivered).as("retried event delivered — zero loss").isNotNull();
    assertThat(failedDelivered.getMessageProperties().getType()).isEqualTo("PaymentFailed");
    assertThat(new String(failedDelivered.getBody(), StandardCharsets.UTF_8))
        .contains(FAILED_PAYMENT_ID);
  }

  private void bindQueue(String queueName, String routingKey) {
    Queue queue = QueueBuilder.durable(queueName).build();
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue).to(new TopicExchange(EXCHANGE, true, false)).with(routingKey));
  }

  private OutboxEvent paymentCompletedRow(String paymentId) {
    String payload =
        ("{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":7,\"amount\":39.98,"
                + "\"currency\":\"EUR\",\"status\":\"SUCCEEDED\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}")
            .formatted(paymentId, ORDER_ID);
    return new OutboxEvent("Payment", paymentId, "PaymentCompleted", payload, Instant.now());
  }

  private OutboxEvent paymentFailedRow(String paymentId) {
    String payload =
        ("{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":7,\"amount\":39.98,"
                + "\"currency\":\"EUR\",\"status\":\"FAILED\",\"failureReason\":\"CARD_DECLINED\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}")
            .formatted(paymentId, ORDER_ID);
    return new OutboxEvent("Payment", paymentId, "PaymentFailed", payload, Instant.now());
  }
}
