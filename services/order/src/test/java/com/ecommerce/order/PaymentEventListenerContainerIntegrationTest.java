package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;

import com.ecommerce.order.client.ProductReservationClient;
import com.ecommerce.order.config.RabbitMQConfig;
import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.InboxEventRepository;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.RabbitTestBroker;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The consumer seam with NOTHING mocked between the broker and the handler: a real RabbitMQ, the
 * real {@code @RabbitListener} container in MANUAL ack mode, the real DLX topology declared from
 * {@link RabbitMQConfig}, and a real Postgres behind the inbox.
 *
 * <p>Every other consumer test calls {@code handle(message, mockChannel)} directly, which proves
 * the branch logic but assumes the broker honours it. Manual-ack semantics, dead-letter routing and
 * "nack without requeue does not loop" live entirely in that assumption — so they are unproven
 * until a real container delivers a real message. This suite is the only place they are exercised.
 */
class PaymentEventListenerContainerIntegrationTest extends AbstractIntegrationTest {

  private static final long USER_ID = 7L;
  private static final String ROUTING_KEY = "payment.completed";

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;
  @Autowired private OrderRepository orderRepository;
  @Autowired private InboxEventRepository inboxEventRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  // The Product call is a separate seam with its own WireMock suites; stubbing it here keeps this
  // test about the broker seam only.
  @MockitoBean private ProductReservationClient reservationClient;

  @BeforeAll
  static void startBroker() {
    RabbitTestBroker.start();
  }

  @DynamicPropertySource
  static void listenerProperties(DynamicPropertyRegistry registry) {
    RabbitTestBroker.registerConnection(registry);
    // Let RabbitAdmin declare Order's own topology (exchange, queue with its x-dead-letter-*
    // arguments, DLX, DLQ) exactly as in production.
    registry.add("spring.rabbitmq.dynamic", () -> "true");
    // The rest of the suite pins this to false and drives the handler directly; here the container
    // MUST run, because the container is what is under test.
    registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
  }

  @BeforeEach
  void reset() {
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
    inboxEventRepository.deleteAll();
    rabbitAdmin.purgeQueue(RabbitMQConfig.PAYMENT_QUEUE, false);
    rabbitAdmin.purgeQueue(RabbitMQConfig.PAYMENT_DLQ, false);
  }

  @Test
  void malformedPayload_realListener_deadLetteredImmediately_noRequeue() {
    byte[] body = "{\"paymentId\":".getBytes(StandardCharsets.UTF_8);

    publish("PaymentCompleted", body);

    Message dead = rabbitTemplate.receive(RabbitMQConfig.PAYMENT_DLQ, 10_000);
    assertThat(dead).as("malformed payload must reach the DLQ").isNotNull();
    assertThat(dead.getBody()).isEqualTo(body);
    assertThat(dead.getMessageProperties().getType()).isEqualTo("PaymentCompleted");

    // x-death.count == 1 is the "no requeue" proof: a requeued message would be re-delivered and
    // dead-lettered again, incrementing the counter.
    Object xDeath = dead.getMessageProperties().getHeaders().get("x-death");
    assertThat(xDeath).as("dead-lettered message must carry x-death").isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> firstDeath = ((List<Map<String, Object>>) xDeath).get(0);
    assertThat(firstDeath.get("count")).isEqualTo(1L);
    assertThat(firstDeath.get("reason")).isEqualTo("rejected");
    assertThat(firstDeath.get("queue")).isEqualTo(RabbitMQConfig.PAYMENT_QUEUE);

    assertThat(readyMessages(RabbitMQConfig.PAYMENT_QUEUE))
        .as("the main queue must not hold the poison message")
        .isZero();
    assertThat(inboxEventRepository.count()).isZero();
    assertThat(orderRepository.count()).isZero();
  }

  @Test
  void duplicateDelivery_realListener_oneInboxRow_oneCommit_nothingDeadLettered() {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-listener-dup";
    orderRepository.save(
        new OrderEntity(
            orderId,
            USER_ID,
            OrderStatus.PENDING,
            "EUR",
            new BigDecimal("39.98"),
            new BigDecimal("39.98"),
            "key-" + orderId));

    byte[] body = completedPayload(paymentId, orderId).getBytes(StandardCharsets.UTF_8);
    publish("PaymentCompleted", body);
    publish("PaymentCompleted", body);

    awaitStatus(orderId, OrderStatus.PAID);

    // The delay lets a second (wrongly non-deduplicated) commit land before the count is asserted.
    verify(reservationClient, after(3_000).times(1)).commit(orderId);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from inbox_events where dedup_key = ? and event_type = ?",
                Integer.class,
                paymentId,
                "PaymentCompleted"))
        .as("at-least-once delivery must leave exactly one dedup row")
        .isEqualTo(1);
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    assertThat(outboxEventRepository.count()).isZero();
    assertThat(rabbitTemplate.receive(RabbitMQConfig.PAYMENT_DLQ, 500))
        .as("a duplicate is a no-op ack, never a dead-letter")
        .isNull();
    assertThat(readyMessages(RabbitMQConfig.PAYMENT_QUEUE)).isZero();
  }

  private void publish(String eventType, byte[] body) {
    MessageProperties props = new MessageProperties();
    props.setType(eventType);
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(RabbitMQConfig.EXCHANGE, ROUTING_KEY, new Message(body, props));
  }

  private String completedPayload(String paymentId, UUID orderId) {
    return String.format(
        "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":%d,\"amount\":39.98,"
            + "\"currency\":\"EUR\",\"status\":\"SUCCEEDED\","
            + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}",
        paymentId, orderId, USER_ID);
  }

  private int readyMessages(String queue) {
    return rabbitAdmin.getQueueInfo(queue).getMessageCount();
  }

  private void awaitStatus(UUID orderId, OrderStatus expected) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (orderRepository.findById(orderId).orElseThrow().getStatus() == expected) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(expected);
  }
}
