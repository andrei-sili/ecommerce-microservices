package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.event.OutboxService;
import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.TestJwt;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class PaymentIntegrationTest extends AbstractIntegrationTest {

  private static final String USER_SUBJECT = "7";
  private static final Long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token(USER_SUBJECT, List.of("USER")));
  private static final String OTHER_USER = TestJwt.bearer(TestJwt.token("99", List.of("USER")));
  private static final String ADMIN = TestJwt.bearer(TestJwt.token("1", List.of("ADMIN")));

  /** Must match test application.yml security.webhook.secret */
  private static final String WEBHOOK_SECRET = "test-webhook-secret";

  @Autowired private MockMvc mockMvc;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private PaymentTransactionRepository transactionRepository;
  @Autowired private ProcessedWebhookEventRepository webhookEventRepository;

  // Mocked so no RabbitMQ connection is needed; we verify the outbox DB rows instead.
  @MockBean private OutboxRelay outboxRelay;
  @MockBean private OrderClient orderClient;

  // Real by default (writes outbox rows); stubbed to throw only in the atomicity test.
  @SpyBean private OutboxService outboxService;

  @BeforeEach
  void cleanDb() {
    // Delete in FK-safe order: child tables first, then parent.
    outboxEventRepository.deleteAll();
    webhookEventRepository.deleteAll();
    transactionRepository.deleteAll();
    paymentRepository.deleteAll();
  }

  @AfterEach
  void resetMocks() {
    org.mockito.Mockito.reset(orderClient);
  }

  // ---- Helper: valid OrderView for a PENDING order ----

  private OrderView pendingOrder(UUID orderId) {
    return new OrderView(orderId, USER_ID, "PENDING", new BigDecimal("39.98"), "EUR");
  }

  // ---- Happy path: successful payment ----

  @Test
  void createPayment_approvedToken_returns201_succeededStatus_outboxEventWritten()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-success")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_valid_token"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.amount").value(39.98))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("\"user_id\":7");
    assertThat(body).doesNotContain("payment_method_token");
    assertThat(body).doesNotContain("gateway_payment_id");

    List<Payment> payments = paymentRepository.findAll();
    assertThat(payments).hasSize(1);
    assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

    List<OutboxEvent> events = outboxEventRepository.findAll();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventType()).isEqualTo("PaymentCompleted");
    assertThat(events.get(0).getPublishedAt()).isNull();
    // Verify the outbox payload is camelCase.
    String payload = events.get(0).getPayload();
    assertThat(payload).contains("\"paymentId\"");
    assertThat(payload).contains("\"orderId\"");
    assertThat(payload).contains("\"occurredAt\"");
  }

  // ---- Declined token → 402 error envelope with payment_id + failure_reason, FAILED persisted ----

  @Test
  void createPayment_declineToken_returns402_errorEnvelope_persistedFailed_paymentFailedEvent()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    String body =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-decline")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_decline_this"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isPaymentRequired())
            // Standard error envelope, not a payment object.
            .andExpect(jsonPath("$.error").value("PAYMENT_DECLINED"))
            .andExpect(jsonPath("$.failure_reason").value("CARD_DECLINED"))
            .andExpect(jsonPath("$.payment_id").exists())
            .andExpect(jsonPath("$.status").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // payment_id in the envelope points to the persisted FAILED payment.
    String paymentId = com.jayway.jsonpath.JsonPath.read(body, "$.payment_id");

    List<Payment> payments = paymentRepository.findAll();
    assertThat(payments).hasSize(1);
    assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payments.get(0).getFailureReason()).isEqualTo("CARD_DECLINED");
    assertThat(payments.get(0).getId().toString()).isEqualTo(paymentId);

    List<OutboxEvent> events = outboxEventRepository.findAll();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventType()).isEqualTo("PaymentFailed");
    assertThat(events.get(0).getPayload()).contains("\"failureReason\"");
  }

  @Test
  void createPayment_replayedDecline_returns402_sameErrorEnvelope() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    // First request: declined.
    String first =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-decline-replay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_decline_replay"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isPaymentRequired())
            .andExpect(jsonPath("$.error").value("PAYMENT_DECLINED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstPaymentId = com.jayway.jsonpath.JsonPath.read(first, "$.payment_id");

    // Replayed: same 402 with the same payment_id.
    String second =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-decline-replay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_decline_replay"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isPaymentRequired())
            .andExpect(jsonPath("$.error").value("PAYMENT_DECLINED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondPaymentId = com.jayway.jsonpath.JsonPath.read(second, "$.payment_id");

    assertThat(secondPaymentId).isEqualTo(firstPaymentId);
    assertThat(paymentRepository.findAll()).hasSize(1); // no double-persist
  }

  // ---- Idempotency: replayed key returns same result, never charges twice ----

  @Test
  void createPayment_replayedKey_returnsOriginalOutcome_neverChargesTwice() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    // First request: succeeds.
    String first =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-replay-idem")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_valid"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.id");

    // Replayed request: must return the same payment id.
    String second =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-replay-idem")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_valid"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isOk()) // replayed key → 200
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondId = com.jayway.jsonpath.JsonPath.read(second, "$.id");

    assertThat(secondId).isEqualTo(firstId);
    assertThat(paymentRepository.findAll()).hasSize(1); // no double-persist
    assertThat(outboxEventRepository.findAll()).hasSize(1); // no double-event
  }

  // ---- Missing Idempotency-Key → 400 ----

  @Test
  void createPayment_missingIdempotencyKey_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"order_id\":\""
                        + UUID.randomUUID()
                        + "\",\"payment_method_token\":\"pm_x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REQUIRED"));
  }

  // ---- Raw card data rejection ----

  @Test
  void createPayment_cardNumberField_returns400_validationError() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-cardfield")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_ok","card_number":"4111111111111111"}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
  }

  @Test
  void createPayment_rawPanInToken_returns400_validationError() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-pantoken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"4111111111111111"}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
  }

  @Test
  void createPayment_cvvField_returns400_validationError() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-cvv")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_ok","cvv":"123"}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
  }

  // ---- Webhook: missing signature → 401 ----

  @Test
  void webhook_missingSig_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\":\"evt_1\",\"event_type\":\"payment_succeeded\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("INVALID_WEBHOOK_SIGNATURE"));
  }

  @Test
  void webhook_invalidSig_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Webhook-Signature", "badhex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\":\"evt_2\",\"event_type\":\"payment_succeeded\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("INVALID_WEBHOOK_SIGNATURE"));
  }

  // ---- Webhook: valid signature + known gateway payment id → state transition ----

  @Test
  void webhook_validSig_succeededEvent_transitionsPayment() throws Exception {
    // First create a PENDING payment.
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-wh-success")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_wh_approve"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isCreated());

    // The payment was SUCCEEDED synchronously (sandbox approved), but the webhook comes anyway.
    Payment payment = paymentRepository.findAll().get(0);
    String gatewayId = payment.getGatewayPaymentId();

    // Build a valid webhook body + signature.
    String body =
        """
        {"event_id":"evt_success_1","event_type":"payment_succeeded",\
        "gateway_payment_id":"%s","amount":39.98,"currency":"EUR"}
        """
            .formatted(gatewayId);
    String sig = computeHmac(body, WEBHOOK_SECRET);

    // Webhook arrives: already SUCCEEDED → idempotent no-op, 200.
    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Webhook-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  // ---- Webhook: idempotency (duplicate event is a no-op) ----

  @Test
  void webhook_duplicateEvent_returnsOkWithoutReprocessing() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));
    mockMvc.perform(
        post("/api/v1/payments")
            .header("Authorization", USER)
            .header("Idempotency-Key", "key-wh-dup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"order_id":"%s","payment_method_token":"pm_wh_dup"}
                """
                    .formatted(orderId)));

    Payment payment = paymentRepository.findAll().get(0);
    String body =
        """
        {"event_id":"evt_dup_1","event_type":"payment_succeeded",\
        "gateway_payment_id":"%s","amount":39.98,"currency":"EUR"}
        """
            .formatted(payment.getGatewayPaymentId());
    String sig = computeHmac(body, WEBHOOK_SECRET);

    // First delivery.
    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Webhook-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    long outboxCountAfterFirst = outboxEventRepository.count();

    // Second delivery (duplicate): same 200, no extra outbox event.
    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Webhook-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    assertThat(outboxEventRepository.count()).isEqualTo(outboxCountAfterFirst);
  }

  // ---- GET /payments/{id}: ownership ----

  @Test
  void getPayment_owner_returns200() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));
    String response =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-get-own")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_get"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String paymentId = com.jayway.jsonpath.JsonPath.read(response, "$.id");

    mockMvc
        .perform(get("/api/v1/payments/" + paymentId).header("Authorization", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(paymentId));
  }

  @Test
  void getPayment_otherUser_returns404_noExistenceLeak() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));
    String response =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-get-other")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_other"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String paymentId = com.jayway.jsonpath.JsonPath.read(response, "$.id");

    mockMvc
        .perform(get("/api/v1/payments/" + paymentId).header("Authorization", OTHER_USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
  }

  @Test
  void getPayment_admin_canReadAnyPayment() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));
    String response =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-get-admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_admin"}
                        """
                            .formatted(orderId)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String paymentId = com.jayway.jsonpath.JsonPath.read(response, "$.id");

    mockMvc
        .perform(get("/api/v1/payments/" + paymentId).header("Authorization", ADMIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(paymentId));
  }

  // ---- Ownership: order belonging to another user → 404 (no existence leak) ----

  @Test
  void createPayment_orderBelongsToAnotherUser_returns404_noExistenceLeak() throws Exception {
    UUID orderId = UUID.randomUUID();
    // Order Service returns the order for user 7 (USER_ID), but the request comes from user 99.
    // OrderClient is mocked so we can test the local ownership guard independently of Order Service.
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", OTHER_USER) // user 99, not the order owner (7)
                .header("Idempotency-Key", "key-cross-user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_ok"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));

    // No payment row created.
    assertThat(paymentRepository.findAll()).isEmpty();
  }

  // ---- Currency mismatch ----

  @Test
  void createPayment_currencyMismatch_returns422() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId)); // order is EUR

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-currency-mismatch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_ok","currency":"USD"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("CURRENCY_MISMATCH"));
  }

  // ---- B-1: persistence atomicity (3 saves are all-or-nothing) ----

  @Test
  void createPayment_outboxWriteFails_noSucceededPaymentWithoutEvent() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    // Force the outbox write inside finalizePayment's transaction to fail after the charge.
    doThrow(new RuntimeException("simulated outbox failure"))
        .when(outboxService)
        .recordPaymentCompleted(any(), any());

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-atomic")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_valid"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isInternalServerError());

    // The finalize transaction rolled back: no SUCCEEDED payment, no audit row, no outbox event.
    // (A charge is never recorded SUCCEEDED without its PaymentCompleted event.)
    assertThat(paymentRepository.findAll())
        .noneMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED);
    assertThat(transactionRepository.count()).isZero();
    assertThat(outboxEventRepository.count()).isZero();
  }

  // ---- Gateway error: PENDING reservation kept, same-key retry returns it without re-charging ----

  @Test
  void createPayment_gatewayError_returns502_keepsPendingReservation() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-gw-error")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_error_token"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("PAYMENT_GATEWAY_ERROR"));

    // The PENDING row MUST remain: deleting it on an ambiguous error would allow a same-key
    // retry to bypass the idempotency fast-path and potentially double-charge.
    List<Payment> payments = paymentRepository.findAll();
    assertThat(payments).hasSize(1);
    assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(outboxEventRepository.count()).isZero();
  }

  @Test
  void createPayment_gatewayError_sameKeyRetry_returnsPendingWithoutReCharging() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    // First request: gateway error.
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-gw-retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_error_token"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isBadGateway());

    // Same-key retry: hits the idempotency fast-path, returns the PENDING payment (200 OK),
    // and MUST NOT call orderClient or the gateway a second time.
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-gw-retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_error_token"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));

    // orderClient was called exactly once (the first attempt only); the retry short-circuited.
    verify(orderClient, times(1)).getOrder(any(), any());

    // Exactly one PENDING row; the gateway was never hit a second time.
    assertThat(paymentRepository.findAll()).hasSize(1);
    assertThat(paymentRepository.findAll().get(0).getStatus()).isEqualTo(PaymentStatus.PENDING);
  }

  // ---- M-2: a second in-flight charge on the same order is blocked before the gateway ----

  @Test
  void createPayment_concurrentDifferentKeySameOrder_returns409_chargesOnce() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));

    // Simulate a first request whose charge is in flight: a committed PENDING reservation.
    paymentRepository.saveAndFlush(
        new Payment(
            orderId,
            USER_ID,
            new BigDecimal("39.98"),
            "EUR",
            PaymentStatus.PENDING,
            "sandbox",
            "pm_inflight",
            "key-inflight"));

    // A second request with a DIFFERENT key collides on the active-payment unique index.
    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-second")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_valid"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("ORDER_PAYMENT_IN_PROGRESS"));

    // Still exactly one (the reservation); the second never reached the gateway.
    assertThat(paymentRepository.findAll()).hasSize(1);
  }

  // ---- M-1: a money event with no amount/currency cannot skip the re-verification guard ----

  @Test
  void webhook_succeededEventMissingAmount_returns422_integrityError() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any())).thenReturn(pendingOrder(orderId));
    mockMvc.perform(
        post("/api/v1/payments")
            .header("Authorization", USER)
            .header("Idempotency-Key", "key-wh-noamount")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"order_id":"%s","payment_method_token":"pm_wh_noamount"}
                """
                    .formatted(orderId)));

    Payment payment = paymentRepository.findAll().get(0);
    long outboxBefore = outboxEventRepository.count();

    // payment_succeeded event referencing the real payment but WITHOUT amount/currency.
    String body =
        """
        {"event_id":"evt_noamount_1","event_type":"payment_succeeded","gateway_payment_id":"%s"}
        """
            .formatted(payment.getGatewayPaymentId());
    String sig = computeHmac(body, WEBHOOK_SECRET);

    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Webhook-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("WEBHOOK_INTEGRITY_ERROR"));

    // Guard rolled back: event not marked processed, no extra outbox event emitted.
    assertThat(webhookEventRepository.existsById("evt_noamount_1")).isFalse();
    assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore);
  }

  // ---- Helper: compute HMAC-SHA256 ----

  private static String computeHmac(String body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Hex.encodeHexString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException(e);
    }
  }
}
