package com.ecommerce.payment.service;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.dto.CreatePaymentRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.WebhookEventRequest;
import com.ecommerce.payment.exception.ApiException;
import com.ecommerce.payment.gateway.GatewayChargeRequest;
import com.ecommerce.payment.gateway.GatewayChargeResult;
import com.ecommerce.payment.gateway.PaymentGatewayClient;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates the payment flow. It owns no DB transaction: every persistence step is delegated to
 * {@link PaymentPersistenceService} so the remote gateway call is never wrapped in an open
 * transaction (saga rule) and the proxy-based {@code @Transactional} boundary actually applies.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentRepository paymentRepository;
  private final ProcessedWebhookEventRepository webhookEventRepository;
  private final PaymentPersistenceService persistence;
  private final PaymentGatewayClient gatewayClient;
  private final OrderClient orderClient;
  private final ObjectMapper objectMapper;
  private final String webhookSecret;

  public PaymentService(
      PaymentRepository paymentRepository,
      ProcessedWebhookEventRepository webhookEventRepository,
      PaymentPersistenceService persistence,
      PaymentGatewayClient gatewayClient,
      OrderClient orderClient,
      ObjectMapper objectMapper,
      @Value("${security.webhook.secret}") String webhookSecret) {
    this.paymentRepository = paymentRepository;
    this.webhookEventRepository = webhookEventRepository;
    this.persistence = persistence;
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
   *
   * <p>On a transient gateway error the PENDING reservation is intentionally kept: deleting it
   * would let a same-key retry bypass the idempotency check and risk a double-charge on an
   * ambiguous error (timeout where the charge may have succeeded). The caller receives 502; a
   * same-key retry returns the existing PENDING payment. Reconciliation (wave 4) resolves the
   * ambiguous row.
   */
  public CreateResult createPayment(
      CurrentUser caller, String idempotencyKey, CreatePaymentRequest request) {

    // (1) Idempotency fast path: a replayed key returns the original outcome verbatim.
    Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      // Scope replay to the caller: another user must not read this payment via the replay path.
      if (!existing.get().getUserId().equals(caller.userId())) {
        throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found");
      }
      return new CreateResult(PaymentResponse.from(existing.get()), true);
    }

    // (2) Load the order via Order Service, forwarding the caller's JWT for ownership check.
    OrderView order = orderClient.getOrder(request.getOrderId(), caller.bearerToken());

    // (2a) Belt-and-suspenders ownership check: Order Service already returns 404 for orders the
    // caller doesn't own, but if user data ever differs across services we catch it here and return
    // 404 (no existence leak) rather than charging the wrong user.
    if (!order.userId().equals(caller.userId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }

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

    // (6) Reserve a PENDING row BEFORE charging. Concurrent attempts on the same order collide on
    // the active-payment unique index here, so the gateway is charged at most once per order.
    Payment reserved;
    try {
      reserved =
          persistence.reservePending(
              caller.userId(),
              request.getOrderId(),
              order.total(),
              order.currency(),
              request.getPaymentMethodToken(),
              idempotencyKey);
    } catch (DataIntegrityViolationException e) {
      // Same idempotency key won the race -> replay its outcome.
      Optional<Payment> replay = paymentRepository.findByIdempotencyKey(idempotencyKey);
      if (replay.isPresent()) {
        // Same ownership check as the fast path.
        if (!replay.get().getUserId().equals(caller.userId())) {
          throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found");
        }
        return new CreateResult(PaymentResponse.from(replay.get()), true);
      }
      // Otherwise another in-flight payment holds this order (active-payment unique index).
      throw new ApiException(
          HttpStatus.CONFLICT,
          "ORDER_PAYMENT_IN_PROGRESS",
          "Another payment for this order is already in progress");
    }

    // (7) Charge the gateway (no DB transaction open during this remote call).
    GatewayChargeRequest chargeReq =
        new GatewayChargeRequest(
            reserved.getId(), request.getPaymentMethodToken(), amountMinorUnits, order.currency());
    GatewayChargeResult result = gatewayClient.charge(chargeReq);

    if (result.gatewayError()) {
      // Leave the PENDING row in place: on an ambiguous error (e.g. timeout where the charge may
      // have succeeded) deleting it would allow a same-key retry to bypass the idempotency check
      // and potentially double-charge. The PENDING row holds the idempotency_key and the
      // active-payment unique index. A same-key retry hits the fast-path at step (1) and returns
      // this PENDING payment; a reconciliation sweep (wave 4) resolves it to SUCCEEDED/FAILED.
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "PAYMENT_GATEWAY_ERROR",
          "Payment gateway is temporarily unavailable");
    }

    // (8) Finalize: status transition + audit transaction + outbox event, atomically.
    Payment payment = persistence.finalizePayment(reserved.getId(), result);
    return new CreateResult(PaymentResponse.from(payment), false);
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
   * Process a gateway webhook. The raw body MUST be verified (HMAC-SHA256) before parsing. Any
   * state transition is applied atomically by {@link PaymentPersistenceService}.
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
      log.warn("Webhook received without event_id -- skipping");
      return;
    }

    // Idempotency fast path: skip duplicate webhook deliveries before opening a transaction.
    if (webhookEventRepository.existsById(event.getEventId())) {
      log.debug("Duplicate webhook event {} -- skipping", event.getEventId());
      return;
    }

    persistence.processVerifiedWebhookEvent(event);
  }

  private void validateOrderPayable(OrderView order, UUID orderId) {
    String status = order.status();
    if ("CANCELLED".equals(status) || "PAYMENT_FAILED".equals(status)) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_NOT_PAYABLE", "Order is not in a payable state");
    }
    if (!"PENDING".equals(status) && !"PAID".equals(status)) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_NOT_PAYABLE", "Order is not in a payable state");
    }
    // Friendly pre-check; the active-payment unique index is the race-safe backstop.
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
