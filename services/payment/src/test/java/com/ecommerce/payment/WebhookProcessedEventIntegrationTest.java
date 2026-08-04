package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.ProcessedWebhookEvent;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.TestJwt;
import com.ecommerce.payment.support.WebhookSignature;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the webhook by its EFFECT, not by its status code. The endpoint answers 200 to a body it
 * could not use — {@code PaymentService} logs a WARN and returns 200 when {@code event_id} is
 * missing, and the persistence service acks an unresolvable {@code gateway_payment_id} against a
 * sentinel payment id. A status-only assertion is therefore satisfied by a webhook that silently
 * does nothing, which is precisely what a snake_case→camelCase binding flip would produce.
 *
 * <p>So the assertions are: the {@code processed_webhook_events} row exists AND is bound to the
 * real payment (never the all-zero sentinel), and a replay of the same event id changes nothing.
 */
class WebhookProcessedEventIntegrationTest extends AbstractIntegrationTest {

  private static final Long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  /** The id {@code PaymentPersistenceService} records when the gateway id resolves to nothing. */
  private static final UUID UNRESOLVED_PAYMENT = new UUID(0, 0);

  @Autowired private MockMvc mockMvc;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private PaymentTransactionRepository transactionRepository;
  @Autowired private ProcessedWebhookEventRepository webhookEventRepository;

  @Value("${security.webhook.secret}")
  private String webhookSecret;

  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    webhookEventRepository.deleteAll();
    transactionRepository.deleteAll();
    paymentRepository.deleteAll();
  }

  @Test
  void signedWebhook_writesProcessedWebhookEventRow_boundToTheRealPayment() throws Exception {
    Payment payment = chargedPayment("key-wh-row");

    deliver("evt_casing_1", payment.getGatewayPaymentId());

    ProcessedWebhookEvent row = webhookEventRepository.findById("evt_casing_1").orElseThrow();
    assertThat(row.getPaymentId())
        .as("the snake_case gateway_payment_id must bind, not fall through to the sentinel")
        .isEqualTo(payment.getId())
        .isNotEqualTo(UNRESOLVED_PAYMENT);
    assertThat(row.getReceivedAt()).isNotNull();
  }

  @Test
  void sameEventIdReplayed_returns200_writesNothingTwice() throws Exception {
    Payment payment = chargedPayment("key-wh-replay");
    String gatewayId = payment.getGatewayPaymentId();

    deliver("evt_replay_1", gatewayId);
    Instant updatedAtAfterFirst =
        paymentRepository.findById(payment.getId()).orElseThrow().getUpdatedAt();
    long transactionsAfterFirst = transactionRepository.count();
    long outboxAfterFirst = outboxEventRepository.count();

    deliver("evt_replay_1", gatewayId);

    assertThat(webhookEventRepository.count()).isEqualTo(1);
    assertThat(transactionRepository.count()).isEqualTo(transactionsAfterFirst);
    assertThat(outboxEventRepository.count()).isEqualTo(outboxAfterFirst);
    assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getUpdatedAt())
        .as("a replayed event must not touch the aggregate")
        .isEqualTo(updatedAtAfterFirst);

    // Control: the dedup is keyed on the event id, not a blanket "the webhook never writes".
    // Without this row, a service that had stopped recording events entirely would still pass.
    deliver("evt_replay_2", gatewayId);
    assertThat(webhookEventRepository.count()).isEqualTo(2);
  }

  private void deliver(String eventId, String gatewayPaymentId) throws Exception {
    String body =
        """
        {"event_id":"%s","event_type":"payment_succeeded",\
        "gateway_payment_id":"%s","amount":39.98,"currency":"EUR"}
        """
            .formatted(eventId, gatewayPaymentId);

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
        .thenReturn(new OrderView(orderId, USER_ID, "PENDING", new BigDecimal("39.98"), "EUR"));

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
