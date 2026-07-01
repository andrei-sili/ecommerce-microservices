package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.order.event.OutboxRelay;
import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Non-mocked seam test for the outbox relay against a REAL RabbitMQ broker (Testcontainers). Proves
 * the "broker confirm != routable" contract end to end with zero mocks on the broker:
 *
 * <ol>
 *   <li><b>RED</b> — the consumer's durable queue ({@code notification.order-events}) is absent, so
 *       an {@code OrderPlaced} publish is silently discarded by the topic exchange and returned as
 *       unroutable. The row must stay {@code published_at IS NULL} (today's buggy relay marks it
 *       published — data loss).
 *   <li><b>GREEN</b> — after the queue is declared + bound, the same row is routed, confirmed, and
 *       marked published; the message is retrievable from the queue (zero loss).
 * </ol>
 *
 * Determinism comes purely from "queue absent vs present" — no reconnect timing, no fixed sleeps
 * (the relay blocks on bounded publisher confirms; message retrieval uses a bounded receive).
 */
class OutboxRelayUnroutableIntegrationTest extends AbstractIntegrationTest {

  private static final String EXCHANGE = "ecommerce.events";
  private static final String ROUTING_KEY = "order.placed";
  private static final String CONSUMER_QUEUE = "notification.order-events";
  private static final String ORDER_ID = "9f1c2e7a-1111-2222-3333-444455556666";

  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(
          DockerImageName.parse("rabbitmq:3-management-alpine")
              .asCompatibleSubstituteFor("rabbitmq"));

  @BeforeAll
  static void startBroker() {
    if (!RABBIT.isRunning()) {
      RABBIT.start();
    }
  }

  @DynamicPropertySource
  static void brokerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    // Let RabbitAdmin declare Order's own topology (the ecommerce.events exchange) at startup.
    registry.add("spring.rabbitmq.dynamic", () -> "true");
    registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
    registry.add("spring.rabbitmq.publisher-returns", () -> "true");
  }

  @Autowired private OutboxRelay outboxRelay;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
    // Order declares its own topology: ecommerce.events (+ its payment-events queue) exists, but
    // notification.order-events (the consumer's durable queue) is deliberately absent here.
    rabbitAdmin.initialize();
  }

  @Test
  void unroutable_thenRoutable_noEventLoss() {
    OutboxEvent row = insertOrderPlacedRow();
    Long id = row.getId();

    // ---- RED: no queue bound to order.placed → message is returned unroutable → NOT published.
    // ----
    outboxRelay.drain();

    OutboxEvent afterFirstDrain = outboxEventRepository.findById(id).orElseThrow();
    assertThat(afterFirstDrain.getPublishedAt())
        .as("unroutable message must NOT be marked published (broker confirm != routable)")
        .isNull();

    // ---- The consumer now declares its durable queue + binding (simulating Notification). ----
    Queue queue = QueueBuilder.durable(CONSUMER_QUEUE).build();
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue).to(new TopicExchange(EXCHANGE, true, false)).with(ROUTING_KEY));

    // ---- GREEN: same row is now routable → confirmed, not returned → published AND delivered.
    // ----
    outboxRelay.drain();

    OutboxEvent afterSecondDrain = outboxEventRepository.findById(id).orElseThrow();
    assertThat(afterSecondDrain.getPublishedAt())
        .as("routable message must be marked published")
        .isNotNull();

    Message delivered = rabbitTemplate.receive(CONSUMER_QUEUE, 5000);
    assertThat(delivered)
        .as("the event must be delivered to the consumer queue — zero loss")
        .isNotNull();
    assertThat(delivered.getMessageProperties().getType()).isEqualTo("OrderPlaced");
    assertThat(new String(delivered.getBody())).contains(ORDER_ID);
  }

  private OutboxEvent insertOrderPlacedRow() {
    return outboxEventRepository.save(
        new OutboxEvent(
            "Order",
            ORDER_ID,
            "OrderPlaced",
            "{\"orderId\":\""
                + ORDER_ID
                + "\",\"userId\":7,\"items\":[{\"productId\":42,\"quantity\":2,"
                + "\"unitPrice\":19.99}],\"total\":39.98,\"currency\":\"EUR\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}",
            Instant.now()));
  }
}
