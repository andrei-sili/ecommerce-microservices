package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.support.AbstractBrokerIntegrationTest;
import com.ecommerce.payment.support.WireEnvelope;
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
 * Non-mocked broker seam test for the Payment outbox relay against a REAL RabbitMQ
 * (Testcontainers), zero broker mocks. Proves "broker confirm != routable" for the money service
 * end to end:
 *
 * <ol>
 *   <li><b>RED</b> — with no queue bound to {@code payment.completed}, a {@code PaymentCompleted}
 *       publish is silently discarded by the topic exchange and returned unroutable. The row MUST
 *       stay {@code published_at IS NULL} (the old relay's {@code waitForConfirmsOrDie} + bulk
 *       {@code markPublished} wrongly marked it — silent loss of the payment-receipt notification).
 *   <li><b>GREEN</b> — after the consumer's durable queue is declared + bound, the same row is
 *       routed, confirmed (not returned), marked published, and retrievable from the queue.
 * </ol>
 *
 * Determinism comes purely from "queue absent vs present" — the relay blocks on bounded publisher
 * confirms; message retrieval uses a bounded receive. No sleeps, no reconnect timing.
 */
class OutboxRelayUnroutableIntegrationTest extends AbstractBrokerIntegrationTest {

  private static final String CONSUMER_QUEUE = "notification.payment-events";
  private static final String PAYMENT_ID = "b1e70000-1111-2222-3333-444455556666";
  private static final String ORDER_ID = "9f1c2e7a-1111-2222-3333-444455556666";

  /** Fixed: AMQP timestamps carry second resolution, so Instant.now() cannot round-trip. */
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-26T10:00:00Z");

  @Autowired private OutboxRelay outboxRelay;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
    // ecommerce.events exists; the consumer's durable queue is deliberately absent so an unbound
    // payment.completed publish is returned unroutable.
    resetSeamTopology(rabbitAdmin);
  }

  @Test
  void unroutable_thenRoutable_noEventLoss() {
    OutboxEvent row = outboxEventRepository.save(paymentCompletedRow());
    Long id = row.getId();

    // ---- RED: no queue bound to payment.completed → returned unroutable → NOT published. ----
    outboxRelay.drain();

    assertThat(outboxEventRepository.findById(id).orElseThrow().getPublishedAt())
        .as("unroutable PaymentCompleted must NOT be marked published (broker confirm != routable)")
        .isNull();

    // ---- The consumer declares its durable queue + binding (simulating Notification/Order). ----
    bindConsumerQueue();

    // ---- GREEN: same row is now routable → confirmed, not returned → published AND delivered.
    // ----
    outboxRelay.drain();

    assertThat(outboxEventRepository.findById(id).orElseThrow().getPublishedAt())
        .as("routable PaymentCompleted must be marked published")
        .isNotNull();

    Message delivered = rabbitTemplate.receive(CONSUMER_QUEUE, 5000);
    assertThat(delivered)
        .as("the event must be delivered to the consumer queue — zero loss")
        .isNotNull();
    WireEnvelope.assertMatches(delivered, outboxEventRepository.findById(id).orElseThrow());
  }

  private void bindConsumerQueue() {
    Queue queue = QueueBuilder.durable(CONSUMER_QUEUE).build();
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue).to(new TopicExchange(EXCHANGE, true, false)).with("payment.*"));
  }

  private OutboxEvent paymentCompletedRow() {
    String payload =
        ("{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":7,\"amount\":39.98,"
                + "\"currency\":\"EUR\",\"status\":\"SUCCEEDED\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}")
            .formatted(PAYMENT_ID, ORDER_ID);
    return new OutboxEvent("Payment", PAYMENT_ID, "PaymentCompleted", payload, OCCURRED_AT);
  }
}
