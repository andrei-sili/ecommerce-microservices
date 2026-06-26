package com.ecommerce.order.event;

import com.ecommerce.order.model.InboxEvent;
import com.ecommerce.order.model.InboxEventId;
import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.InboxEventRepository;
import com.ecommerce.order.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional business logic for payment event consumption. Each method executes in a single DB
 * transaction: inbox-dedup row + order state change commit atomically. The consumer calls these
 * from outside a transaction so that acking the message only happens after this commit returns.
 */
@Service
public class PaymentEventHandlerService {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventHandlerService.class);

  private final OrderRepository orderRepository;
  private final InboxEventRepository inboxEventRepository;

  public PaymentEventHandlerService(
      OrderRepository orderRepository, InboxEventRepository inboxEventRepository) {
    this.orderRepository = orderRepository;
    this.inboxEventRepository = inboxEventRepository;
  }

  public enum HandlerResult {
    NO_OP,
    PAID_COMMITTED,
    NEED_RELEASE
  }

  /**
   * Process a {@code PaymentCompleted} event. On success returns {@link
   * HandlerResult#PAID_COMMITTED} so the caller can invoke the Product commit endpoint (outside
   * this tx). On a duplicate delivery returns {@link HandlerResult#NO_OP} (ack, skip). Throws
   * {@link PermanentConsumerFailureException} on amount/currency mismatch or anomalous order state
   * (caller must nack to DLQ).
   */
  @Transactional
  public HandlerResult processPaymentCompleted(PaymentEvent event) {
    InboxEventId inboxId = new InboxEventId(event.paymentId(), "PaymentCompleted");

    // Idempotency: if already processed, return no-op ack.
    if (inboxEventRepository.existsById(inboxId)) {
      log.debug("Duplicate PaymentCompleted for paymentId={}, skipping", event.paymentId());
      return HandlerResult.NO_OP;
    }

    UUID orderId = toUuid(event.orderId());
    OrderEntity order =
        orderRepository
            .findById(orderId)
            .orElseThrow(
                () ->
                    new PermanentConsumerFailureException(
                        "Order not found for PaymentCompleted: " + event.orderId()));

    // Defense-in-depth: verify the charged amount matches what Order recorded at placement.
    if (order.getTotal().compareTo(event.amount()) != 0) {
      log.error(
          "CRITICAL reconciliation: amount mismatch for order {} — expected {} got {}",
          orderId,
          order.getTotal(),
          event.amount());
      throw new PermanentConsumerFailureException("Amount mismatch for order " + orderId);
    }
    if (!order.getCurrency().trim().equals(event.currency())) {
      log.error(
          "CRITICAL reconciliation: currency mismatch for order {} — expected '{}' got '{}'",
          orderId,
          order.getCurrency(),
          event.currency());
      throw new PermanentConsumerFailureException("Currency mismatch for order " + orderId);
    }

    OrderStatus status = order.getStatus();

    if (status == OrderStatus.PAID) {
      // Already PAID (race condition between dedup check and here): safe to record and ack.
      inboxEventRepository.save(
          new InboxEvent(event.paymentId(), "PaymentCompleted", Instant.now()));
      return HandlerResult.NO_OP;
    }

    if (status == OrderStatus.CANCELLED || status == OrderStatus.PAYMENT_FAILED) {
      log.error(
          "ANOMALY: PaymentCompleted received for {} order {} paymentId={}",
          status,
          orderId,
          event.paymentId());
      throw new PermanentConsumerFailureException(
          "PaymentCompleted for terminal order " + orderId + " state=" + status);
    }

    // PENDING → PAID in one tx + inbox dedup row.
    order.setStatus(OrderStatus.PAID);
    orderRepository.save(order);
    inboxEventRepository.save(new InboxEvent(event.paymentId(), "PaymentCompleted", Instant.now()));

    return HandlerResult.PAID_COMMITTED;
    // Transaction commits here; Product commit is called by the consumer after this returns.
  }

  /**
   * Process a {@code PaymentFailed} event. PENDING → PAYMENT_FAILED + release reservation. Already
   * terminal → no-op ack.
   */
  @Transactional
  public HandlerResult processPaymentFailed(PaymentEvent event) {
    InboxEventId inboxId = new InboxEventId(event.paymentId(), "PaymentFailed");
    if (inboxEventRepository.existsById(inboxId)) {
      log.debug("Duplicate PaymentFailed for paymentId={}, skipping", event.paymentId());
      return HandlerResult.NO_OP;
    }

    UUID orderId = toUuid(event.orderId());
    OrderEntity order =
        orderRepository
            .findById(orderId)
            .orElseThrow(
                () ->
                    new PermanentConsumerFailureException(
                        "Order not found for PaymentFailed: " + event.orderId()));

    inboxEventRepository.save(new InboxEvent(event.paymentId(), "PaymentFailed", Instant.now()));

    if (order.getStatus() != OrderStatus.PENDING) {
      log.debug("PaymentFailed for non-PENDING order {}, no-op", orderId);
      return HandlerResult.NO_OP;
    }

    order.setStatus(OrderStatus.PAYMENT_FAILED);
    orderRepository.save(order);
    return HandlerResult.NEED_RELEASE;
  }

  /**
   * Process a {@code PaymentCancelled} event. PENDING → CANCELLED + release reservation. Already
   * terminal → no-op ack.
   */
  @Transactional
  public HandlerResult processPaymentCancelled(PaymentEvent event) {
    InboxEventId inboxId = new InboxEventId(event.paymentId(), "PaymentCancelled");
    if (inboxEventRepository.existsById(inboxId)) {
      log.debug("Duplicate PaymentCancelled for paymentId={}, skipping", event.paymentId());
      return HandlerResult.NO_OP;
    }

    UUID orderId = toUuid(event.orderId());
    OrderEntity order =
        orderRepository
            .findById(orderId)
            .orElseThrow(
                () ->
                    new PermanentConsumerFailureException(
                        "Order not found for PaymentCancelled: " + event.orderId()));

    inboxEventRepository.save(new InboxEvent(event.paymentId(), "PaymentCancelled", Instant.now()));

    if (order.getStatus() != OrderStatus.PENDING) {
      log.debug("PaymentCancelled for non-PENDING order {}, no-op", orderId);
      return HandlerResult.NO_OP;
    }

    order.setStatus(OrderStatus.CANCELLED);
    orderRepository.save(order);
    return HandlerResult.NEED_RELEASE;
  }

  private UUID toUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new PermanentConsumerFailureException("Invalid orderId UUID: " + raw);
    }
  }
}
