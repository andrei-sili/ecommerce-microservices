package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.dto.WebhookEventRequest;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.model.ProcessedWebhookEvent;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.service.PaymentPersistenceService;
import com.ecommerce.payment.service.PaymentService;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.TestJwt;
import com.ecommerce.payment.support.WebhookSignature;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The webhook pinned by its EFFECT rather than its status code, and the replay pinned so that
 * deleting the idempotency guards is RED.
 *
 * <p><b>This now carries B11 in-suite.</b> It previously could not: payment's {@code
 * src/test/resources/application.yml} shadowed the shipped file, so the snake_case naming strategy
 * these assertions depend on was the TEST yml's and a casing flip in production config stayed
 * invisible here. Since the shadow became a profile overlay ({@code application-test.yml}), the
 * webhook body below binds through the SHIPPED {@code spring.jackson.property-naming-strategy};
 * flipping it there makes {@code event_id}/{@code gateway_payment_id} stop binding and this class
 * goes RED. Compose/smoke evidence at the container remains the on-the-wire confirmation, not the
 * only proof.
 *
 * <p>The endpoint answers 200 to a body it could not use: {@code PaymentService} logs a WARN and
 * returns 200 when {@code event_id} is missing, and the persistence service acks an unresolvable
 * {@code gateway_payment_id} against a sentinel id. A status-only assertion is therefore satisfied
 * by a webhook that silently does nothing — exactly what a snake_case→camelCase binding flip would
 * produce on every real gateway callback.
 *
 * <p><b>Why the replay row sends a DIFFERENT body the second time.</b> Both idempotency guards can
 * be deleted and a same-body replay still changes nothing, because the state machine absorbs it:
 * {@code transitionToSucceeded} returns early on an already-SUCCEEDED payment, and re-saving the
 * ledger row is a no-op ({@code received_at} is {@code updatable = false}). Counts, timestamps and
 * outbox rows are all identical with and without the guards, so a same-body replay test is green
 * either way — it proves the state machine, not the dedup. Replaying the same event id with a
 * changed amount separates them: the guards short-circuit before the body is read (200, nothing
 * happens), while an unguarded service reaches {@code verifyMoneyIntegrity} and answers 422.
 */
class WebhookProcessedEventIntegrationTest extends AbstractIntegrationTest {

  private static final Long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  private static final BigDecimal CHARGED_AMOUNT = new BigDecimal("39.98");

  /** What a replayed event id claims the second time. Never applied — the guards see it first. */
  private static final String TAMPERED_AMOUNT = "999.99";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PaymentPersistenceService persistence;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private PaymentTransactionRepository transactionRepository;
  @Autowired private ProcessedWebhookEventRepository webhookEventRepository;

  @Value("${security.webhook.secret}")
  private String webhookSecret;

  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  private Logger serviceLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> serviceLog;

  @BeforeEach
  void cleanDbAndCaptureLog() {
    outboxEventRepository.deleteAll();
    webhookEventRepository.deleteAll();
    transactionRepository.deleteAll();
    paymentRepository.deleteAll();

    serviceLogger = (Logger) LoggerFactory.getLogger(PaymentService.class);
    originalLevel = serviceLogger.getLevel();
    serviceLogger.setLevel(Level.DEBUG);
    serviceLog = new ListAppender<>();
    serviceLog.start();
    serviceLogger.addAppender(serviceLog);
  }

  @AfterEach
  void detachLog() {
    if (serviceLogger != null) {
      serviceLogger.detachAppender(serviceLog);
      serviceLogger.setLevel(originalLevel);
    }
  }

  @Test
  void signedWebhook_writesProcessedWebhookEventRow_boundToTheRealPayment() throws Exception {
    Payment payment = chargedPayment("key-wh-row");

    deliver("evt_casing_1", payment.getGatewayPaymentId(), CHARGED_AMOUNT.toPlainString());

    assertThat(webhookEventRepository.existsById("evt_casing_1"))
        .as("a signed snake_case webhook must leave its row -- 200 alone proves nothing")
        .isTrue();
    ProcessedWebhookEvent row = webhookEventRepository.findById("evt_casing_1").orElseThrow();
    assertThat(row.getPaymentId())
        .as("the snake_case gateway_payment_id must bind, not fall through to the sentinel")
        .isEqualTo(payment.getId());
    assertThat(row.getReceivedAt()).isNotNull();
  }

  @Test
  void sameEventIdReplayed_isACompleteNoOp_evenWhenTheBodyChanged() throws Exception {
    Payment payment = chargedPayment("key-wh-replay");
    String gatewayId = payment.getGatewayPaymentId();

    deliver("evt_replay_1", gatewayId, CHARGED_AMOUNT.toPlainString());
    Instant updatedAtAfterFirst =
        paymentRepository.findById(payment.getId()).orElseThrow().getUpdatedAt();
    long transactionsAfterFirst = transactionRepository.count();
    long outboxAfterFirst = outboxEventRepository.count();
    serviceLog.list.clear();

    // Same event id, correctly signed, claiming a different amount. Guarded: 200, ignored.
    // Unguarded: verifyMoneyIntegrity compares 999.99 against 39.98 and this call answers 422.
    deliver("evt_replay_1", gatewayId, TAMPERED_AMOUNT);

    assertThat(loggedContaining("Duplicate webhook event evt_replay_1"))
        .as("the fast-path dedup must fire; without it the body is parsed and acted on")
        .isTrue();
    assertThat(webhookEventRepository.count()).isEqualTo(1);
    assertThat(transactionRepository.count()).isEqualTo(transactionsAfterFirst);
    assertThat(outboxEventRepository.count()).isEqualTo(outboxAfterFirst);

    Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(after.getAmount())
        .as("a replayed event id must never restate the amount")
        .isEqualByComparingTo(CHARGED_AMOUNT);
    assertThat(after.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(after.getUpdatedAt())
        .as("a replayed event must not touch the aggregate")
        .isEqualTo(updatedAtAfterFirst);

    // Control: the dedup is keyed on the event id, not a blanket "the webhook never writes".
    // Without this row, a service that had stopped recording events entirely would still pass.
    deliver("evt_replay_2", gatewayId, CHARGED_AMOUNT.toPlainString());
    assertThat(webhookEventRepository.count()).isEqualTo(2);
  }

  /**
   * The SECOND idempotency guard, isolated -- and the abuse it stops.
   *
   * <p>The changed-amount replay above cannot reach this guard: the fast path in {@code
   * PaymentService} short-circuits before the body is ever read, so deleting {@code
   * PaymentPersistenceService}'s in-transaction re-check leaves the whole suite green. This test
   * therefore calls {@code processVerifiedWebhookEvent} directly, bypassing the fast path by
   * construction.
   *
   * <p>What it protects is not academic. {@code ProcessedWebhookEvent.paymentId} carries no {@code
   * updatable = false} -- only {@code receivedAt} does -- so a replayed event id pointing at a
   * DIFFERENT {@code gateway_payment_id} with a MATCHING amount clears {@code
   * verifyMoneyIntegrity}, no-ops through the state machine (the second payment is already
   * terminal), and then JPA-merges the audit ledger row onto the other payment. Row count stays 1,
   * {@code updated_at} is untouched, the outbox is unchanged -- every count-based assertion in this
   * class is blind to it. The ledger would simply record that a gateway event belonged to a payment
   * it never belonged to.
   */
  @Test
  void replayedEventId_withAnotherGatewayId_doesNotRebindTheLedgerRow() throws Exception {
    Payment first = seedSucceeded("gw_ledger_first", "key-ledger-1");
    Payment second = seedSucceeded("gw_ledger_second", "key-ledger-2");

    persistence.processVerifiedWebhookEvent(webhookEvent("evt_ledger_1", "gw_ledger_first"));
    assertThat(webhookEventRepository.findById("evt_ledger_1").orElseThrow().getPaymentId())
        .isEqualTo(first.getId());

    persistence.processVerifiedWebhookEvent(webhookEvent("evt_ledger_1", "gw_ledger_second"));

    assertThat(webhookEventRepository.findById("evt_ledger_1").orElseThrow().getPaymentId())
        .as("a replayed event id must never rebind the ledger row to another payment")
        .isEqualTo(first.getId())
        .isNotEqualTo(second.getId());
    // Fails only if the dedup ever INSERTS instead of merging. Kept next to the binding assertion
    // above as the demonstration that counting rows cannot see this bug.
    assertThat(webhookEventRepository.count()).isEqualTo(1);
  }

  /** Parses a snake_case body the way the endpoint does, so the wire casing stays in the loop. */
  private WebhookEventRequest webhookEvent(String eventId, String gatewayPaymentId)
      throws Exception {
    String body =
        ("{\"event_id\":\"%s\",\"event_type\":\"payment_succeeded\","
                + "\"gateway_payment_id\":\"%s\",\"amount\":39.98,\"currency\":\"EUR\"}")
            .formatted(eventId, gatewayPaymentId);
    return objectMapper.readValue(body, WebhookEventRequest.class);
  }

  /** A terminal payment carrying a resolvable gateway id, on its own order. */
  private Payment seedSucceeded(String gatewayPaymentId, String idempotencyKey) {
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            USER_ID,
            CHARGED_AMOUNT,
            "EUR",
            PaymentStatus.SUCCEEDED,
            "sandbox",
            "pm_seeded",
            idempotencyKey);
    payment.setGatewayPaymentId(gatewayPaymentId);
    return paymentRepository.saveAndFlush(payment);
  }

  private boolean loggedContaining(String fragment) {
    return serviceLog.list.stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
  }

  private void deliver(String eventId, String gatewayPaymentId, String amount) throws Exception {
    String body =
        """
        {"event_id":"%s","event_type":"payment_succeeded",\
        "gateway_payment_id":"%s","amount":%s,"currency":"EUR"}
        """
            .formatted(eventId, gatewayPaymentId, amount);

    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Webhook-Signature", WebhookSignature.of(body, webhookSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  /** Drives the real charge path so the payment carries a gateway id the webhook can resolve. */
  private Payment chargedPayment(String idempotencyKey) throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any()))
        .thenReturn(new OrderView(orderId, USER_ID, "PENDING", CHARGED_AMOUNT, "EUR"));

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header("Authorization", USER)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"order_id":"%s","payment_method_token":"pm_valid_token"}
                    """
                        .formatted(orderId)))
        .andExpect(status().isCreated());

    return paymentRepository.findAll().get(0);
  }
}
