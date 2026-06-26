package com.ecommerce.payment.service;

import com.ecommerce.payment.dto.WebhookEventRequest;
import com.ecommerce.payment.event.OutboxService;
import com.ecommerce.payment.exception.ApiException;
import com.ecommerce.payment.gateway.GatewayChargeResult;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.model.PaymentTransaction;
import com.ecommerce.payment.model.ProcessedWebhookEvent;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every local DB transaction of the payment flow. Deliberately a separate bean from {@link
 * PaymentService}: Spring applies {@code @Transactional} only across a proxy boundary, so the
 * orchestrating service MUST call these public methods on the injected bean (never self-invoke) for
 * the transaction to actually open. This also keeps the remote gateway call outside any open DB
 * transaction (saga rule: never hold a tx across a remote call).
 */
@Service
public class PaymentPersistenceService {

  private static final Logger log = LoggerFactory.getLogger(PaymentPersistenceService.class);

  private static final String GATEWAY_NAME = "sandbox";
  private static final UUID NO_PAYMENT = new UUID(0, 0);

  private final PaymentRepository paymentRepository;
  private final PaymentTransactionRepository transactionRepository;
  private final ProcessedWebhookEventRepository webhookEventRepository;
  private final OutboxService outboxService;

  public PaymentPersistenceService(
      PaymentRepository paymentRepository,
      PaymentTransactionRepository transactionRepository,
      ProcessedWebhookEventRepository webhookEventRepository,
      OutboxService outboxService) {
    this.paymentRepository = paymentRepository;
    this.transactionRepository = transactionRepository;
    this.webhookEventRepository = webhookEventRepository;
    this.outboxService = outboxService;
  }

  /**
   * Reserve a PENDING payment row before the gateway is charged. The commit makes the reservation
   * visible to concurrent attempts on the same order, which then collide on the {@code
   * uix_payments_order_active} unique index — so the gateway is charged at most once per order. The
   * flush forces any constraint violation to surface here as a {@code
   * DataIntegrityViolationException } rather than at a later commit boundary.
   */
  @Transactional
  public Payment reservePending(
      Long userId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String paymentMethodToken,
      String idempotencyKey) {
    Payment payment =
        new Payment(
            orderId,
            userId,
            amount,
            currency,
            PaymentStatus.PENDING,
            GATEWAY_NAME,
            paymentMethodToken,
            idempotencyKey);
    return paymentRepository.saveAndFlush(payment);
  }

  /**
   * Finalize a reserved payment atomically: the status transition, the audit transaction, and the
   * outbox event are one all-or-nothing unit. If the outbox write fails, the whole unit rolls back,
   * so a charge is NEVER left SUCCEEDED without its {@code PaymentCompleted} event — the row stays
   * PENDING for reconciliation.
   */
  @Transactional
  public Payment finalizePayment(UUID paymentId, GatewayChargeResult result) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Reserved payment not found"));

    PaymentStatus status = result.approved() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
    payment.setStatus(status);
    if (result.gatewayPaymentId() != null) {
      payment.setGatewayPaymentId(result.gatewayPaymentId());
    }
    if (result.failureReason() != null) {
      payment.setFailureReason(result.failureReason());
    }
    paymentRepository.save(payment);

    String txType = result.approved() ? "CAPTURE" : "AUTHORIZE";
    transactionRepository.save(
        new PaymentTransaction(
            payment.getId(),
            txType,
            status.name(),
            payment.getAmount(),
            payment.getCurrency(),
            result.gatewayPaymentId(),
            result.approved()
                ? "Payment captured"
                : "Payment declined: " + result.failureReason()));

    Instant occurredAt = Instant.now();
    if (result.approved()) {
      outboxService.recordPaymentCompleted(payment, occurredAt);
    } else {
      outboxService.recordPaymentFailed(payment, occurredAt);
    }
    return payment;
  }

  /**
   * Drop a reservation after a transient gateway error so the order (and the idempotency key) are
   * free to retry. The gateway charge did not complete, so no terminal state is recorded.
   */
  @Transactional
  public void releaseReservation(UUID paymentId) {
    paymentRepository.deleteById(paymentId);
  }

  /**
   * Apply a verified webhook event in a single transaction. The signature MUST already have been
   * verified by the caller. Any state transition writes its outbox event in this same transaction.
   */
  @Transactional
  public void processVerifiedWebhookEvent(WebhookEventRequest event) {
    // Re-check idempotency inside the transaction for race safety.
    if (webhookEventRepository.existsById(event.getEventId())) {
      return;
    }

    Payment payment = findPaymentByGatewayId(event.getGatewayPaymentId());
    if (payment == null) {
      log.warn(
          "Webhook {} references unknown gateway payment id {} -- acking without processing",
          event.getEventId(),
          event.getGatewayPaymentId());
      webhookEventRepository.save(new ProcessedWebhookEvent(event.getEventId(), NO_PAYMENT));
      return;
    }

    String eventType = event.getEventType();
    verifyMoneyIntegrity(event, payment, eventType);

    Instant occurredAt = Instant.now();
    if ("payment_succeeded".equals(eventType)) {
      transitionToSucceeded(payment, event, occurredAt);
    } else if ("payment_failed".equals(eventType)) {
      transitionToFailed(payment, event, occurredAt);
    } else if ("payment_canceled".equals(eventType)) {
      transitionToCancelled(payment, event, occurredAt);
    } else {
      log.info("Webhook {} has unknown event type {} -- acking", event.getEventId(), eventType);
    }

    webhookEventRepository.save(new ProcessedWebhookEvent(event.getEventId(), payment.getId()));
  }

  /**
   * Defense-in-depth re-verification of a money-moving event. For {@code payment_succeeded}/{@code
   * payment_failed} the amount and currency are REQUIRED and must match the stored payment; a
   * missing or mismatched value is a hard validation error (routes to DLQ) rather than a silent
   * skip. Cancellation events carry no money and are exempt.
   */
  private void verifyMoneyIntegrity(WebhookEventRequest event, Payment payment, String eventType) {
    boolean moneyEvent =
        "payment_succeeded".equals(eventType) || "payment_failed".equals(eventType);
    if (!moneyEvent) {
      return;
    }
    if (event.getAmount() == null || event.getCurrency() == null) {
      log.error(
          "RECONCILIATION: webhook {} for payment {} missing amount/currency -- routing to DLQ",
          event.getEventId(),
          payment.getId());
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "WEBHOOK_INTEGRITY_ERROR",
          "Webhook is missing amount/currency required for verification");
    }
    boolean mismatch =
        event.getAmount().compareTo(payment.getAmount()) != 0
            || !event.getCurrency().equalsIgnoreCase(payment.getCurrency());
    if (mismatch) {
      log.error(
          "RECONCILIATION: webhook {} amount/currency mismatch for payment {} -- routing to DLQ",
          event.getEventId(),
          payment.getId());
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "AMOUNT_MISMATCH",
          "Webhook amount/currency does not match stored payment");
    }
  }

  private void transitionToSucceeded(Payment payment, WebhookEventRequest event, Instant now) {
    if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
      return; // already terminal -- idempotent no-op
    }
    if (payment.getStatus() != PaymentStatus.PENDING) {
      log.warn("Webhook succeeded but payment {} is {}", payment.getId(), payment.getStatus());
      return;
    }
    payment.setStatus(PaymentStatus.SUCCEEDED);
    payment.setGatewayPaymentId(event.getGatewayPaymentId());
    paymentRepository.save(payment);
    logWebhookTransaction(payment, event, "SUCCEEDED");
    outboxService.recordPaymentCompleted(payment, now);
  }

  private void transitionToFailed(Payment payment, WebhookEventRequest event, Instant now) {
    if (payment.getStatus() == PaymentStatus.FAILED) {
      return;
    }
    if (payment.getStatus() != PaymentStatus.PENDING) {
      log.warn("Webhook failed but payment {} is {}", payment.getId(), payment.getStatus());
      return;
    }
    payment.setStatus(PaymentStatus.FAILED);
    if (event.getFailureReason() != null) {
      payment.setFailureReason(event.getFailureReason());
    }
    paymentRepository.save(payment);
    logWebhookTransaction(payment, event, "FAILED");
    outboxService.recordPaymentFailed(payment, now);
  }

  private void transitionToCancelled(Payment payment, WebhookEventRequest event, Instant now) {
    if (payment.getStatus() == PaymentStatus.CANCELLED) {
      return;
    }
    if (payment.getStatus() != PaymentStatus.PENDING) {
      log.warn("Webhook canceled but payment {} is {}", payment.getId(), payment.getStatus());
      return;
    }
    payment.setStatus(PaymentStatus.CANCELLED);
    paymentRepository.save(payment);
    logWebhookTransaction(payment, event, "CANCELLED");
    outboxService.recordPaymentCancelled(payment, now);
  }

  private void logWebhookTransaction(Payment payment, WebhookEventRequest event, String status) {
    transactionRepository.save(
        new PaymentTransaction(
            payment.getId(),
            "WEBHOOK",
            status,
            payment.getAmount(),
            payment.getCurrency(),
            event.getEventId(),
            "Webhook: " + event.getEventType()));
  }

  private Payment findPaymentByGatewayId(String gatewayPaymentId) {
    if (gatewayPaymentId == null) {
      return null;
    }
    return paymentRepository.findByGatewayPaymentId(gatewayPaymentId).orElse(null);
  }
}
