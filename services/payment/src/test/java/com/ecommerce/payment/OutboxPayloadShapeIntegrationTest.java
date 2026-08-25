package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.JsonShape;
import com.ecommerce.payment.support.TestJwt;
import com.ecommerce.payment.support.WebhookSignature;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
 * Freezes the outbox payload shape per event type. The relay ships {@code outbox_events.payload}
 * verbatim, so the JSONB text asserted here IS the wire format every consumer parses.
 *
 * <p>Event payloads are camelCase — the opposite convention from the REST bodies, produced by a
 * dedicated {@code JsonMapper} in {@code OutboxService} rather than by Boot's mapper. Two
 * independent things must therefore hold and are pinned separately: the exact key set (camelCase,
 * with {@code failureReason} carried ONLY by {@code PaymentFailed}) and {@code occurredAt} being an
 * ISO-8601 <b>String</b>, which today rests entirely on that mapper's explicit {@code
 * WRITE_DATES_AS_TIMESTAMPS} disable.
 */
class OutboxPayloadShapeIntegrationTest extends AbstractIntegrationTest {

  private static final Long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

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
  void approvedCharge_paymentCompletedPayload_exactCamelCaseKeySet_isoOccurredAt()
      throws Exception {
    charge("key-outbox-completed", "pm_valid_token", status().isCreated());

    String payload = onlyPayloadOfType("PaymentCompleted");

    assertThat(JsonShape.keysOf(payload))
        .containsExactlyInAnyOrder(
            "paymentId", "orderId", "userId", "amount", "currency", "status", "occurredAt");
    assertThat(JsonPath.<String>read(payload, "$.status")).isEqualTo("SUCCEEDED");
    JsonShape.assertIso8601Utc(payload, "occurredAt");

    // Money and ids stay unquoted JSON numbers. This replaced a doesNotContain("failureReason")
    // that was unfalsifiable -- PaymentCompletedPayload is a record with no such component, so no
    // change to the codebase could ever have made that line fail. WRITE_NUMBERS_AS_STRINGS on this
    // mapper would break every consumer's amount arithmetic silently; these two lines go red on it.
    JsonShape.assertJsonNumber(payload, "amount", 39.98);
    JsonShape.assertJsonNumber(payload, "userId", 7);
  }

  @Test
  void declinedCharge_paymentFailedPayload_exactCamelCaseKeySet_withFailureReason()
      throws Exception {
    charge("key-outbox-failed", "pm_decline_this", status().isPaymentRequired());

    String payload = onlyPayloadOfType("PaymentFailed");

    assertThat(JsonShape.keysOf(payload))
        .containsExactlyInAnyOrder(
            "paymentId",
            "orderId",
            "userId",
            "amount",
            "currency",
            "status",
            "failureReason",
            "occurredAt");
    assertThat(JsonPath.<String>read(payload, "$.status")).isEqualTo("FAILED");
    assertThat(JsonPath.<String>read(payload, "$.failureReason")).isEqualTo("CARD_DECLINED");
    JsonShape.assertIso8601Utc(payload, "occurredAt");
  }

  /**
   * Third payload shape. The cancel path is not reachable end to end on this build — the gateway id
   * is assigned only on approval, so "PENDING with a gateway id set" never occurs naturally — which
   * is why the recorded AC-0.4 amendment seeds that ONE field by name for {@code PaymentCancelled}
   * and then drives the REAL path: real signature verification, real persistence service, real
   * mapper. The payload asserted below is genuine serializer output.
   */
  @Test
  void canceledWebhook_paymentCancelledPayload_exactCamelCaseKeySet() throws Exception {
    Payment pending =
        new Payment(
            UUID.randomUUID(),
            USER_ID,
            new BigDecimal("9.95"),
            "EUR",
            PaymentStatus.PENDING,
            "sandbox",
            "pm_cancel_seed",
            "key-outbox-cancelled");
    pending.setGatewayPaymentId("gw_seeded_for_cancel");
    UUID paymentId = paymentRepository.saveAndFlush(pending).getId();

    String body =
        """
        {"event_id":"evt_cancel_shape_1","event_type":"payment_canceled",\
        "gateway_payment_id":"gw_seeded_for_cancel"}
        """;
    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Webhook-Signature", WebhookSignature.of(body, webhookSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.CANCELLED);

    String payload = onlyPayloadOfType("PaymentCancelled");

    assertThat(JsonShape.keysOf(payload))
        .containsExactlyInAnyOrder(
            "paymentId", "orderId", "userId", "amount", "currency", "status", "occurredAt");
    assertThat(JsonPath.<String>read(payload, "$.status")).isEqualTo("CANCELLED");
    assertThat(JsonPath.<String>read(payload, "$.paymentId")).isEqualTo(paymentId.toString());
    JsonShape.assertIso8601Utc(payload, "occurredAt");
  }

  private String onlyPayloadOfType(String eventType) {
    List<OutboxEvent> events =
        outboxEventRepository.findAll().stream()
            .filter(e -> eventType.equals(e.getEventType()))
            .toList();
    assertThat(events).as("exactly one %s row", eventType).hasSize(1);
    return events.get(0).getPayload();
  }

  private void charge(
      String idempotencyKey,
      String paymentMethodToken,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus)
      throws Exception {
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
                    {"order_id":"%s","payment_method_token":"%s"}
                    """
                        .formatted(orderId, paymentMethodToken)))
        .andExpect(expectedStatus);
  }
}
