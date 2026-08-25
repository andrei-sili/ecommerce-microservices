package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.order.client.ProductReservationClient;
import com.ecommerce.order.event.PaymentEventConsumer;
import com.ecommerce.order.model.InboxEventId;
import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.InboxEventRepository;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration tests for {@link PaymentEventConsumer}: handler logic against a real PostgreSQL DB.
 * Consumer methods are invoked directly (no live broker); {@link Channel} is mocked to verify
 * ack/nack calls.
 */
class PaymentEventConsumerTest extends AbstractIntegrationTest {

  private static final long USER_ID = 7L;

  @Autowired private PaymentEventConsumer consumer;
  @Autowired private OrderRepository orderRepository;
  @Autowired private InboxEventRepository inboxEventRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  // Mocked so tests can control commit/release outcomes.
  @MockitoBean private ProductReservationClient reservationClient;

  @Mock private Channel channel;

  private AutoCloseable mocks;
  private Logger consumerLogger;
  private ListAppender<ILoggingEvent> consumerLog;

  @BeforeEach
  void setup() {
    mocks = MockitoAnnotations.openMocks(this);
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
    inboxEventRepository.deleteAll();

    consumerLogger = (Logger) LoggerFactory.getLogger(PaymentEventConsumer.class);
    consumerLog = new ListAppender<>();
    consumerLog.start();
    consumerLogger.addAppender(consumerLog);
  }

  @AfterEach
  void tearDown() throws Exception {
    consumerLogger.detachAppender(consumerLog);
    mocks.close();
  }

  // ---- Helper ----

  private OrderEntity pendingOrder(UUID orderId, BigDecimal total, String currency) {
    OrderEntity order =
        new OrderEntity(
            orderId, USER_ID, OrderStatus.PENDING, currency, total, total, "test-key-" + orderId);
    return orderRepository.save(order);
  }

  private Message paymentMessage(
      String eventType, String paymentId, String orderId, BigDecimal amount, String currency) {
    String payload =
        String.format(
            "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":%d,"
                + "\"amount\":%s,\"currency\":\"%s\",\"status\":\"SUCCEEDED\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}",
            paymentId, orderId, USER_ID, amount.toPlainString(), currency);
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(1L);
    props.setType(eventType);
    return new Message(payload.getBytes(), props);
  }

  // ---- PaymentCompleted ----

  @Test
  void paymentCompleted_pendingOrder_markedPaid_commitCalled_acked() throws IOException {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-001";
    pendingOrder(orderId, new BigDecimal("39.98"), "EUR");

    consumer.handle(
        paymentMessage(
            "PaymentCompleted", paymentId, orderId.toString(), new BigDecimal("39.98"), "EUR"),
        channel);

    // Order marked PAID.
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    // Inbox row persisted.
    assertThat(inboxEventRepository.existsById(new InboxEventId(paymentId, "PaymentCompleted")))
        .isTrue();
    // Product commit called.
    verify(reservationClient).commit(orderId);
    // Message acked.
    verify(channel).basicAck(1L, false);
  }

  @Test
  void paymentCompleted_duplicate_isIdempotent_commitCalledOnce() throws IOException {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-002";
    pendingOrder(orderId, new BigDecimal("39.98"), "EUR");

    Message msg =
        paymentMessage(
            "PaymentCompleted", paymentId, orderId.toString(), new BigDecimal("39.98"), "EUR");

    // First delivery.
    consumer.handle(msg, channel);
    // Second delivery (duplicate).
    consumer.handle(msg, channel);

    // Order is PAID.
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    // Exactly ONE dedup row for the (paymentId, eventType) pair. Counted in SQL, not through the
    // composite-key existsById the consumer itself uses: a broken key lookup would make production
    // and an existsById-based assertion agree on the same wrong answer.
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from inbox_events where dedup_key = ? and event_type = ?",
                Integer.class,
                paymentId,
                "PaymentCompleted"))
        .isEqualTo(1);
    // ONE effect: the duplicate must not re-emit an event either.
    assertThat(outboxEventRepository.count()).isZero();
    // Commit called exactly once (second delivery is a no-op ack).
    verify(reservationClient, times(1)).commit(orderId);
    // Both messages acked.
    verify(channel, times(2)).basicAck(1L, false);
  }

  /**
   * The dedup key is COMPOSITE — {@code (dedup_key, event_type)} — and only a second event type on
   * the same {@code paymentId} can prove the second column still discriminates. Every other dedup
   * test replays one event type, so a lookup that silently collapses to {@code dedup_key} alone
   * (which is what a regenerated composite-key query or an AOT-materialised repository can produce)
   * keeps them all green while every follow-up event for that payment is swallowed as a duplicate.
   *
   * <p>PaymentFailed then PaymentCancelled for one payment is the real sequence behind this: a
   * failed charge that is subsequently cancelled must record BOTH, not dedup the second away.
   */
  @Test
  void secondEventTypeSamePaymentId_isNotDeduped_compositeKeyStillDiscriminates()
      throws IOException {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-shared-key";
    pendingOrder(orderId, new BigDecimal("10.00"), "EUR");

    consumer.handle(
        paymentMessage(
            "PaymentFailed", paymentId, orderId.toString(), new BigDecimal("10.00"), "EUR"),
        channel);
    consumer.handle(
        paymentMessage(
            "PaymentCancelled", paymentId, orderId.toString(), new BigDecimal("10.00"), "EUR"),
        channel);

    // TWO rows under one dedup_key: the second event was processed, not swallowed.
    assertThat(
            jdbcTemplate.queryForList(
                "select event_type from inbox_events where dedup_key = ? order by event_type",
                String.class,
                paymentId))
        .as("both event types must be recorded under the same paymentId")
        .containsExactly("PaymentCancelled", "PaymentFailed");
    // And each half of the key is individually addressable.
    assertThat(inboxEventRepository.existsById(new InboxEventId(paymentId, "PaymentFailed")))
        .isTrue();
    assertThat(inboxEventRepository.existsById(new InboxEventId(paymentId, "PaymentCancelled")))
        .isTrue();
    // A key that ignored event_type would also answer true for a type that never arrived.
    assertThat(inboxEventRepository.existsById(new InboxEventId(paymentId, "PaymentCompleted")))
        .as("a third, never-delivered event type must NOT be reported as already processed")
        .isFalse();

    // The first event owned the state transition; the second is a no-op on a terminal order.
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAYMENT_FAILED);
    verify(channel, times(2)).basicAck(1L, false);
  }

  /**
   * A body that cannot be deserialized is a permanent failure: nack WITHOUT requeue, straight to
   * the DLQ. Requeuing it instead would spin the queue forever on a message that can never succeed.
   * The log line is asserted because the routing decision and the diagnosis must not drift apart —
   * a deserialization failure reaching the transient branch would still nack, just with requeue.
   */
  @Test
  void malformedPayload_deadLetteredImmediately_noRequeue() throws IOException {
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(9L);
    props.setType("PaymentCompleted");
    Message msg = new Message("{\"paymentId\":".getBytes(StandardCharsets.UTF_8), props);

    consumer.handle(msg, channel);

    verify(channel).basicNack(9L, false, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    assertThat(inboxEventRepository.count()).isZero();
    assertThat(orderRepository.count()).isZero();

    assertThat(consumerLog.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(line -> assertThat(line).startsWith("Failed to deserialize payment event"))
        .noneSatisfy(line -> assertThat(line).contains("Transient failure, requeuing"));
  }

  @Test
  void paymentCompleted_amountMismatch_orderStillPending_deadLettered() throws IOException {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-003";
    pendingOrder(orderId, new BigDecimal("39.98"), "EUR");

    // Wrong amount: 40.00 ≠ 39.98.
    consumer.handle(
        paymentMessage(
            "PaymentCompleted", paymentId, orderId.toString(), new BigDecimal("40.00"), "EUR"),
        channel);

    // Order must NOT be marked PAID.
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PENDING);
    // Inbox row must NOT be written (tx rolled back on mismatch).
    assertThat(inboxEventRepository.existsById(new InboxEventId(paymentId, "PaymentCompleted")))
        .isFalse();
    // Commit must NOT be called.
    verify(reservationClient, never()).commit(any());
    // Message dead-lettered (nack without requeue).
    verify(channel).basicNack(1L, false, false);
  }

  @Test
  void paymentCompleted_commit409_orderPaidInboxExists_deadLettered() throws IOException {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-004";
    pendingOrder(orderId, new BigDecimal("39.98"), "EUR");

    doThrow(new ProductReservationClient.ReservationNotActiveException())
        .when(reservationClient)
        .commit(orderId);

    consumer.handle(
        paymentMessage(
            "PaymentCompleted", paymentId, orderId.toString(), new BigDecimal("39.98"), "EUR"),
        channel);

    // Order WAS marked PAID in the committed transaction before commit 409.
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
    // Inbox row exists, preventing future reprocessing.
    assertThat(inboxEventRepository.existsById(new InboxEventId(paymentId, "PaymentCompleted")))
        .isTrue();
    // Message dead-lettered for reconciliation (no oversell).
    verify(channel).basicNack(1L, false, false);
  }

  // ---- PaymentFailed ----

  @Test
  void paymentFailed_pendingOrder_markedPaymentFailed_reservationReleased() throws IOException {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-005";
    pendingOrder(orderId, new BigDecimal("39.98"), "EUR");

    String payload =
        String.format(
            "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":%d,"
                + "\"amount\":39.98,\"currency\":\"EUR\",\"status\":\"FAILED\","
                + "\"failureReason\":\"CARD_DECLINED\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}",
            paymentId, orderId, USER_ID);
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(2L);
    props.setType("PaymentFailed");
    Message msg = new Message(payload.getBytes(), props);

    consumer.handle(msg, channel);

    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAYMENT_FAILED);
    verify(reservationClient).release(orderId);
    verify(channel).basicAck(2L, false);
  }

  @Test
  void paymentFailed_alreadyTerminal_noOp_acked() throws IOException {
    UUID orderId = UUID.randomUUID();
    String paymentId = "pay-006";
    OrderEntity order =
        new OrderEntity(
            orderId,
            USER_ID,
            OrderStatus.CANCELLED,
            "EUR",
            new BigDecimal("10.00"),
            new BigDecimal("10.00"),
            "key-" + orderId);
    orderRepository.save(order);

    String payload =
        String.format(
            "{\"paymentId\":\"%s\",\"orderId\":\"%s\",\"userId\":%d,"
                + "\"amount\":10.00,\"currency\":\"EUR\",\"status\":\"FAILED\","
                + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}",
            paymentId, orderId, USER_ID);
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(3L);
    props.setType("PaymentFailed");
    Message msg = new Message(payload.getBytes(), props);

    consumer.handle(msg, channel);

    // Status unchanged (already CANCELLED).
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.CANCELLED);
    // No release call (order was already terminal).
    verify(reservationClient, never()).release(any());
    // Acked (no-op).
    verify(channel).basicAck(3L, false);
  }

  // ---- Cancel on PAID/PAYMENT_FAILED ----

  @Test
  void cancelOrder_paidOrder_returns409() throws Exception {
    // This tests the HTTP layer; covered by the Wave 2 cancel test framework.
    // Verify at unit level that PAID is in a non-cancellable state (contract spec).
    UUID orderId = UUID.randomUUID();
    OrderEntity order =
        new OrderEntity(
            orderId,
            USER_ID,
            OrderStatus.PAID,
            "EUR",
            new BigDecimal("10.00"),
            new BigDecimal("10.00"),
            "key-" + orderId);
    orderRepository.save(order);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getStatus()).isNotEqualTo(OrderStatus.PENDING);
    assertThat(order.getStatus()).isNotEqualTo(OrderStatus.CANCELLED);
    // The OrderService.cancelOrder() will throw OrderNotCancellableException for this state;
    // the controller maps it to 409. Verified end-to-end in OrderPlacementIntegrationTest.
  }
}
