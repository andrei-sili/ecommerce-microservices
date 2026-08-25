package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.order.event.OutboxRelay;
import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
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
    // NB: publisher-confirm-type=correlated + publisher-returns=true are intentionally NOT set
    // here — they are sourced from application.yml (mirroring production), so reverting them there
    // turns this test RED. Only container-specific wiring is overridden above.
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
    // Clean slate: a prior test may have declared the durable consumer queue; remove it so the
    // "no binding → unroutable" precondition holds for every test (queueDelete is idempotent).
    rabbitAdmin.deleteQueue(CONSUMER_QUEUE);
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
    bindConsumerQueue();

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
    assertWireEnvelope(delivered, afterSecondDrain);
  }

  /**
   * Per-row marking under a MIXED batch drained in a single pass. The relay derives one routing key
   * ({@code order.placed}) for Order's only event type, so a "routable + returned in the same
   * drain" pair isn't constructible without a second routing key; instead this proves:
   *
   * <ol>
   *   <li>a batch of several unroutable rows ALL stay {@code published_at IS NULL} (the fix holds
   *       for N&gt;1, not just the single-row path — an ack-only relay would wrongly mark every
   *       one);
   *   <li>in one drain that mixes routable rows with a non-publishable row, ONLY the routable rows
   *       get {@code published_at} while the other stays NULL and is retried; and
   *   <li>every routable row is delivered to the consumer queue (zero loss across a batch).
   * </ol>
   */
  @Test
  void mixedBatch_onlyRoutableRowsMarked_othersStayNull() {
    OutboxEvent a = insertRow("11111111-aaaa-1111-aaaa-111111111111", "OrderPlaced");
    OutboxEvent b = insertRow("22222222-bbbb-2222-bbbb-222222222222", "OrderPlaced");
    // A row the relay never sends (unmapped type) — must never be marked, mixed in the same batch.
    OutboxEvent skipped = insertRow("33333333-cccc-3333-cccc-333333333333", "OrderShipped");

    // ---- Batch drain with NO binding: both OrderPlaced rows are returned unroutable. ----
    outboxRelay.drain();

    assertThat(outboxEventRepository.findById(a.getId()).orElseThrow().getPublishedAt())
        .as("row A (unroutable) must stay NULL in a batch")
        .isNull();
    assertThat(outboxEventRepository.findById(b.getId()).orElseThrow().getPublishedAt())
        .as("row B (unroutable) must stay NULL in a batch")
        .isNull();
    assertThat(outboxEventRepository.findById(skipped.getId()).orElseThrow().getPublishedAt())
        .as("unmapped row must stay NULL")
        .isNull();

    // ---- Bind the queue, then drain a MIXED batch: A + B routable, the unmapped row still not.
    // ----
    bindConsumerQueue();
    outboxRelay.drain();

    assertThat(outboxEventRepository.findById(a.getId()).orElseThrow().getPublishedAt())
        .as("row A must be marked published once routable")
        .isNotNull();
    assertThat(outboxEventRepository.findById(b.getId()).orElseThrow().getPublishedAt())
        .as("row B must be marked published once routable")
        .isNotNull();
    assertThat(outboxEventRepository.findById(skipped.getId()).orElseThrow().getPublishedAt())
        .as("the unmapped row must remain NULL in the mixed drain — only routable rows are marked")
        .isNull();

    // Both routable events are delivered (zero loss across the batch); exactly two, no duplicates.
    // Delivery order across a batch is not contractual, so each message is matched back to its row
    // by message_id and then asserted in full.
    Map<String, Message> deliveredById = new HashMap<>();
    for (int i = 0; i < 2; i++) {
      Message msg = rabbitTemplate.receive(CONSUMER_QUEUE, 5000);
      assertThat(msg).as("expected a delivered message from the batch").isNotNull();
      deliveredById.put(msg.getMessageProperties().getMessageId(), msg);
    }
    assertThat(deliveredById.keySet())
        .as("both routable rows delivered, none lost, none duplicated")
        .containsExactlyInAnyOrder(String.valueOf(a.getId()), String.valueOf(b.getId()));
    assertWireEnvelope(
        deliveredById.get(String.valueOf(a.getId())),
        outboxEventRepository.findById(a.getId()).orElseThrow());
    assertWireEnvelope(
        deliveredById.get(String.valueOf(b.getId())),
        outboxEventRepository.findById(b.getId()).orElseThrow());
    assertThat(rabbitTemplate.receive(CONSUMER_QUEUE, 500))
        .as("no extra/duplicate delivery")
        .isNull();
  }

  /**
   * The full wire envelope, not a substring of the body. {@code .contains(orderId)} passed through
   * any key reorder, whitespace change or dropped field, and asserted nothing at all about the AMQP
   * properties a consumer routes and deduplicates on.
   */
  private void assertWireEnvelope(Message delivered, OutboxEvent row) {
    assertThat(new String(delivered.getBody(), StandardCharsets.UTF_8))
        .as("the relay ships the payload column verbatim — it must not re-serialize it")
        .isEqualTo(row.getPayload());

    MessageProperties props = delivered.getMessageProperties();
    assertThat(props.getType()).isEqualTo(row.getEventType());
    assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    assertThat(props.getMessageId()).isEqualTo(String.valueOf(row.getId()));
    // AMQP timestamps carry second precision, so the row's occurred_at is compared truncated.
    assertThat(props.getTimestamp())
        .isEqualTo(Date.from(row.getOccurredAt().truncatedTo(ChronoUnit.SECONDS)));
    assertThat(props.getReceivedExchange()).isEqualTo(EXCHANGE);
    assertThat(props.getReceivedRoutingKey()).isEqualTo(ROUTING_KEY);
  }

  private void bindConsumerQueue() {
    Queue queue = QueueBuilder.durable(CONSUMER_QUEUE).build();
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(queue).to(new TopicExchange(EXCHANGE, true, false)).with(ROUTING_KEY));
  }

  private OutboxEvent insertOrderPlacedRow() {
    return insertRow(ORDER_ID, "OrderPlaced");
  }

  private OutboxEvent insertRow(String orderId, String eventType) {
    return outboxEventRepository.save(
        new OutboxEvent(
            "Order",
            orderId,
            eventType,
            "{\"orderId\":\""
                + orderId
                + "\",\"userId\":7,\"items\":[{\"productId\":42,\"quantity\":2,"
                + "\"unitPrice\":19.99}],\"total\":39.98,\"currency\":\"EUR\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}",
            Instant.now()));
  }
}
