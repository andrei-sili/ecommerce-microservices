package com.ecommerce.payment.service;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.dto.CreatePaymentRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.WebhookEventRequest;
import com.ecommerce.payment.event.OutboxService;
import com.ecommerce.payment.exception.ApiException;
import com.ecommerce.payment.gateway.GatewayChargeRequest;
import com.ecommerce.payment.gateway.GatewayChargeResult;
import com.ecommerce.payment.gateway.PaymentGatewayClient;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.model.PaymentTransaction;
import com.ecommerce.payment.model.ProcessedWebhookEvent;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private static final String GATEWAY_NAME = "sandbox";

  private final PaymentRepository paymentRepository;
  private final PaymentTransactionRepository transactionRepository;
  private final ProcessedWebhookEventRepository webhookEventRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final OutboxService outboxService;
  private final PaymentGatewayClient gatewayClient;
  private final OrderClient orderClient;
  private final ObjectMapper objectMapper;
  private final String webhookSecret;

  public PaymentService(
      PaymentRepository paymentRepository,
      PaymentTransactionRepository transactionRepository,
      ProcessedWebhookEventRepository webhookEventRepository,
      OutboxEventRepository outboxEventRepository,
      OutboxService outboxService,
      PaymentGatewayClient gatewayClient,
      OrderClient orderClient,
      ObjectMapper objectMapper,
      @Value("${security.webhook.secret}") String webhookSecret) {
    this.paymentRepository = paymentRepository;
    this.transactionRepository = transactionRepository;
    this.webhookEventRepository = webhookEventRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.outboxService = outboxService;
    this.gatewayClient = gatewayClient;
    this.orderClient = orderClient;
    this.objectMapper = objectMapper;
    this.webhookSecret = webhookSecret;
  }

  /**
   * Result of a create-payment attempt: the response plus whether it was a replay (so the
   * controller knows to return 200 vs 201).
   */
  public record CreateResult(PaymentResponse response, boolean replayed) {}

  /**
   * Initiate payment for an order. All charges are at-least-once safe via the Idempotency-Key. The
   * amount is always read server-side from Order Service; no client-supplied amount is used.
   */
  public CreateResult createPayment(
      CurrentUser caller, String idempotencyKey, CreatePaymentRequest request) {

    // (1) Idempotency fast path: a replayed key returns the original outcome verbatim.
    Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      Payment p = existing.get();
      // Return the same status so the controller can mirror the original HTTP status code.
      return new CreateResult(PaymentResponse.from(p), true);
    }

    // (2) Load the order via Order Service, forwarding the caller's JWT for ownership check.
    OrderView order = orderClient.getOrder(request.getOrderId(), caller.bearerToken());

    // (3) Currency guard: if the client supplied a currency it must match the order.
    if (request.getCurrency() != null
        && !request.getCurrency().equalsIgnoreCase(order.currency())) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CURRENCY_MISMATCH",
          "Requested currency does not match the order currency");
    }

    // (4) Order state guards.
    validateOrderPayable(order, request.getOrderId());

    // (5) Compute minor units (integer cents) from the server-authoritative order total.
    long amountMinorUnits = toMinorUnits(order.total());

    // (6) Charge the gateway (no DB transaction open during this remote call).
    GatewayChargeRequest chargeReq =
        new GatewayChargeRequest(
            UUID.randomUUID(), request.getPaymentMethodToken(), amountMinorUnits, order.currency());
    GatewayChargeResult result = gatewayClient.charge(chargeReq);

    if (result.gatewayError()) {
      // Transient gateway error: no terminal state persisted; idempotency key allows safe retry.
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "PAYMENT_GATEWAY_ERROR",
          "Payment gateway is temporarily unavailable");
    }

    // (7) Persist payment + transaction + outbox event in one atomic transaction.
    Payment payment =
        persistPaymentResult(
            caller.userId(),
            request.getOrderId(),
            order.total(),
            order.currency(),
            request.getPaymentMethodToken(),
            idempotencyKey,
            result);

    boolean succeeded = payment.getStatus() == PaymentStatus.SUCCEEDED;
    return new CreateResult(PaymentResponse.from(payment), false);
  }

  @Transactional
  protected Payment persistPaymentResult(
      Long userId,
      UUID orderId,
      BigDecimal amount,
      String currency,
      String paymentMethodToken,
      String idempotencyKey,
      GatewayChargeResult result) {

    PaymentStatus status = result.approved() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
    Payment payment =
        new Payment(
            orderId,
            userId,
            amount,
            currency,
            status,
            GATEWAY_NAME,
            paymentMethodToken,
            idempotencyKey);
    if (result.gatewayPaymentId() != null) {
      payment.setGatewayPaymentId(result.gatewayPaymentId());
    }
    if (result.failureReason() != null) {
      payment.setFailureReason(result.failureReason());
    }
    paymentRepository.save(payment);

    // Audit transaction (no card data).
    String txType = result.approved() ? "CAPTURE" : "AUTHORIZE";
    transactionRepository.save(
        new PaymentTransaction(
            payment.getId(),
            txType,
            status.name(),
            amount,
            currency,
            result.gatewayPaymentId(),
            result.approved()
                ? "Payment captured"
                : "Payment declined: " + result.failureReason()));

    // Outbox event in the same transaction.
    Instant occurredAt = Instant.now();
    if (result.approved()) {
      outboxService.recordPaymentCompleted(payment, occurredAt);
    } else {
      outboxService.recordPaymentFailed(payment, occurredAt);
    }

    return payment;
  }

  @Transactional(readOnly = true)
  public PaymentResponse getPayment(CurrentUser caller, UUID paymentId) {
    Payment payment;
    if (caller.isAdmin()) {
      payment =
          paymentRepository
              .findById(paymentId)
              .orElseThrow(
                  () ->
                      new ApiException(
                          HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
    } else {
      payment =
          paymentRepository
              .findByIdAndUserId(paymentId, caller.userId())
              .orElseThrow(
                  () ->
                      new ApiException(
                          HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
    }
    return PaymentResponse.from(payment);
  }

  /**
   * Process a gateway webhook. The raw body MUST already have been verified (HMAC-SHA256) by the
   * caller before this method is invoked. Any state transition writes to the outbox in the same
   * transaction.
   *
   * @param rawBody verified raw request bytes
   * @param signature the {@code X-Webhook-Signature} header value
   * @throws ApiException 401 INVALID_WEBHOOK_SIGNATURE on bad/missing signature
   */
  public void processWebhook(byte[] rawBody, String signature) {
    verifyWebhookSignature(rawBody, signature);

    WebhookEventRequest event;
    try {
      event = objectMapper.readValue(rawBody, WebhookEventRequest.class);
    } catch (Exception e) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "MALFORMED_WEBHOOK", "Webhook body is not valid JSON");
    }

    if (event.getEventId() == null || event.getEventId().isBlank()) {
      log.warn("Webhook received without event_id — skipping");
      return;
    }

    // Idempotency: skip duplicate webhook deliveries.
    if (webhookEventRepository.existsById(event.getEventId())) {
      log.debug("Duplicate webhook event {} — skipping", event.getEventId());
      return;
    }

    processVerifiedWebhookEvent(event);
  }

  @Transactional
  protected void processVerifiedWebhookEvent(WebhookEventRequest event) {
    // Already checked idempotency outside the transaction; re-check inside for race safety.
    if (webhookEventRepository.existsById(event.getEventId())) {
      return;
    }

    Payment payment = findPaymentByGatewayId(event.getGatewayPaymentId());
    if (payment == null) {
      log.warn(
          "Webhook {} references unknown gateway payment id {} — acking without processing",
          event.getEventId(),
          event.getGatewayPaymentId());
      // Record so we don't re-process; associate with a zero UUID since we have no payment id.
      webhookEventRepository.save(new ProcessedWebhookEvent(event.getEventId(), new UUID(0, 0)));
      return;
    }

    // Amount + currency integrity check (defense-in-depth).
    if (event.getAmount() != null && event.getCurrency() != null) {
      boolean amountMismatch =
          event.getAmount().compareTo(payment.getAmount()) != 0
              || !event.getCurrency().equalsIgnoreCase(payment.getCurrency());
      if (amountMismatch) {
        log.error(
            "RECONCILIATION: webhook {} amount/currency mismatch for payment {} — routing to DLQ",
            event.getEventId(),
            payment.getId());
        // Do NOT update state. Record the event to stop retries, but flag it.
        webhookEventRepository.save(new ProcessedWebhookEvent(event.getEventId(), payment.getId()));
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "AMOUNT_MISMATCH",
            "Webhook amount/currency does not match stored payment");
      }
    }

    Instant occurredAt = Instant.now();
    String eventType = event.getEventType();

    if ("payment_succeeded".equals(eventType)) {
      transitionToSucceeded(payment, event, occurredAt);
    } else if ("payment_failed".equals(eventType)) {
      transitionToFailed(payment, event, occurredAt);
    } else if ("payment_canceled".equals(eventType)) {
      transitionToCancelled(payment, event, occurredAt);
    } else {
      log.info("Webhook {} has unknown event type {} — acking", event.getEventId(), eventType);
    }

    webhookEventRepository.save(new ProcessedWebhookEvent(event.getEventId(), payment.getId()));
  }

  private void transitionToSucceeded(Payment payment, WebhookEventRequest event, Instant now) {
    if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
      return; // already in terminal state — idempotent no-op
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
    if (gatewayPaymentId == null) return null;
    return paymentRepository.findByGatewayPaymentId(gatewayPaymentId).orElse(null);
  }

  private void validateOrderPayable(OrderView order, UUID orderId) {
    String status = order.status();
    if ("PAID".equals(status)) {
      // Check DB for existing SUCCEEDED payment.
      if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.SUCCEEDED)) {
        throw new ApiException(
            HttpStatus.CONFLICT, "ORDER_ALREADY_PAID", "This order has already been paid");
      }
    }
    if ("CANCELLED".equals(status) || "PAYMENT_FAILED".equals(status)) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_NOT_PAYABLE", "Order is not in a payable state");
    }
    if (!"PENDING".equals(status) && !"PAID".equals(status)) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_NOT_PAYABLE", "Order is not in a payable state");
    }
    // Extra DB check: if a SUCCEEDED payment already exists for this order (regardless of order
    // status), reject to prevent any double-charge path.
    if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.SUCCEEDED)) {
      throw new ApiException(
          HttpStatus.CONFLICT, "ORDER_ALREADY_PAID", "This order has already been paid");
    }
  }

  /** Verify the HMAC-SHA256 webhook signature before any processing. */
  private void verifyWebhookSignature(byte[] rawBody, String signature) {
    if (signature == null || signature.isBlank()) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", "Missing webhook signature");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] expected = mac.doFinal(rawBody);
      String expectedHex = Hex.encodeHexString(expected);
      // Constant-time comparison to prevent timing attacks.
      if (!MessageDigest.isEqual(
          expectedHex.getBytes(StandardCharsets.UTF_8),
          signature.getBytes(StandardCharsets.UTF_8))) {
        throw new ApiException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_WEBHOOK_SIGNATURE",
            "Webhook signature verification failed");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 not available", e);
    }
  }

  /** Convert a decimal amount to minor units (integer cents) for gateway charging. */
  private static long toMinorUnits(BigDecimal amount) {
    return amount
        .setScale(2, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .longValueExact();
  }
}
