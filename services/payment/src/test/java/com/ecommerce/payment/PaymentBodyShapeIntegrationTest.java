package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.JsonShape;
import com.ecommerce.payment.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Freezes the JSON shape of payment's money bodies: the 201 charge, the 402 decline envelope and
 * the 401 auth envelope, each pinned by its EXACT key set plus the format of every date field.
 *
 * <p>Why the exact set and not a subset: payment renders all three through getter POJOs (not
 * records), so their wire shape depends on getter discovery AND on the global {@code
 * default-property-inclusion: non_null} that suppresses {@code failure_reason} on a success. The
 * regression this guards against is a body <b>gaining</b> a null-valued key, which every
 * subset-style assertion passes straight through.
 *
 * <p>The absence assertions are not vacuous: {@link
 * #failedPayment_get_returns200_withFailureReason} is their positive control — the same field, same
 * POJO, present the moment it is non-null.
 */
class PaymentBodyShapeIntegrationTest extends AbstractIntegrationTest {

  private static final Long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  /** The baseline 401 probe path, captured verbatim in {@code 401-envelopes-3516.txt}. */
  private static final String UNKNOWN_PAYMENT_PATH =
      "/api/v1/payments/00000000-0000-0000-0000-000000000000";

  @Autowired private MockMvc mockMvc;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private PaymentTransactionRepository transactionRepository;
  @Autowired private ProcessedWebhookEventRepository webhookEventRepository;

  // Same override shape as the rest of the suite so this class reuses the cached context.
  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    webhookEventRepository.deleteAll();
    transactionRepository.deleteAll();
    paymentRepository.deleteAll();
  }

  /**
   * AC-5.4. The key SET is the primary assertion — {@code failure_reason} ABSENT is what proves
   * {@code default-property-inclusion: non_null} still applies (B4) — and the VALUES are asserted
   * beside it, because a key set alone cannot see a field bound to the wrong source.
   *
   * <p>Key ORDER is deliberately not asserted. {@code PaymentResponse} is a getter POJO, not a
   * record, and Jackson 3 enables {@code SORT_PROPERTIES_ALPHABETICALLY} with no creator properties
   * to keep ahead of it, so this body reorders at 4.1.1 (A18). The set and the values are what the
   * contract binds.
   *
   * <p>The raw-text absences are not redundant with the exact key set: the set covers TOP-LEVEL
   * keys only, so a camelCase duplicate nested anywhere, or a token leaking inside a string value,
   * would pass it. {@code payment_method_token} and {@code gateway_payment_id} are the two that
   * must never reach a client at all.
   */
  @Test
  void approvedCharge_returns201_exactKeySet_failureReasonAbsent() throws Exception {
    String body = charge("key-shape-201", "pm_valid_token", status().isCreated());

    assertThat(JsonShape.keysOf(body))
        .containsExactlyInAnyOrder(
            "id",
            "order_id",
            "user_id",
            "amount",
            "currency",
            "status",
            "gateway",
            "created_at",
            "updated_at");

    assertThat(JsonPath.<Integer>read(body, "$.user_id")).isEqualTo(7);
    JsonShape.assertJsonNumber(body, "amount", 39.98);
    assertThat(JsonPath.<String>read(body, "$.currency")).isEqualTo("EUR");
    assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("SUCCEEDED");
    JsonShape.assertIso8601Utc(body, "created_at");

    assertThat(body)
        .as("snake_case is the REST convention; a camelCase key means the naming strategy moved")
        .doesNotContain("\"orderId\"")
        .doesNotContain("\"createdAt\"");
    assertThat(body)
        .as("neither the payment-method token nor the gateway id may ever reach a client")
        .doesNotContain("payment_method_token")
        .doesNotContain("gateway_payment_id");
  }

  @Test
  void approvedCharge_returns201_createdAtAndUpdatedAtAreIso8601Strings() throws Exception {
    String body = charge("key-shape-dates", "pm_valid_token", status().isCreated());

    JsonShape.assertIso8601Utc(body, "created_at");
    JsonShape.assertIso8601Utc(body, "updated_at");
  }

  /**
   * Positive control for both absence assertions above: on a FAILED payment the very same POJO and
   * the very same serializer DO emit {@code failure_reason}. Without this row, "the key is missing"
   * would also be satisfied by a serializer that had stopped emitting the field entirely.
   */
  @Test
  void failedPayment_get_returns200_withFailureReason() throws Exception {
    String declined = charge("key-shape-failed", "pm_decline_this", status().isPaymentRequired());
    String paymentId = JsonPath.read(declined, "$.payment_id");

    String body =
        mockMvc
            .perform(get("/api/v1/payments/" + paymentId).header("Authorization", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.failure_reason").value("CARD_DECLINED"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonShape.keysOf(body))
        .containsExactlyInAnyOrder(
            "id",
            "order_id",
            "user_id",
            "amount",
            "currency",
            "status",
            "gateway",
            "failure_reason",
            "created_at",
            "updated_at");
    JsonShape.assertIso8601Utc(body, "created_at");
    JsonShape.assertIso8601Utc(body, "updated_at");
  }

  @Test
  void declinedCharge_returns402_exactSixKeyEnvelope() throws Exception {
    String body = charge("key-shape-402", "pm_decline_this", status().isPaymentRequired());

    assertThat(JsonShape.keysOf(body))
        .containsExactlyInAnyOrder(
            "error", "message", "timestamp", "path", "payment_id", "failure_reason");
    assertThat(JsonPath.<String>read(body, "$.error")).isEqualTo("PAYMENT_DECLINED");
    assertThat(JsonPath.<String>read(body, "$.message"))
        .isEqualTo("Payment was declined: CARD_DECLINED");
    assertThat(JsonPath.<String>read(body, "$.path")).isEqualTo("/api/v1/payments");
    assertThat(JsonPath.<String>read(body, "$.failure_reason")).isEqualTo("CARD_DECLINED");
    JsonShape.assertIso8601Utc(body, "timestamp");
  }

  /**
   * The auth envelope is the NARROW one: exactly four keys. The exact key set is the assertion —
   * this used to also carry {@code doesNotContain("payment_id")}, which was <b>unfalsifiable</b>:
   * the 401 is rendered from {@code ErrorResponse}, a four-field POJO with no such property, so no
   * change to the codebase could ever have made that line fail.
   *
   * <p><b>The Content-Type assertion below is the MockMvc-normalised value, NOT the wire value.</b>
   * This class previously asserted that the entry point (Path A, {@code response.getWriter()}) and
   * the converter stack (Path B) render the same {@code Content-Type}. That was green here and
   * FALSE in production: MockMvc normalises the encoding away, while a real Tomcat appends its
   * default charset to Path A and nothing to Path B — {@code application/json;charset=ISO-8859-1}
   * vs {@code application/json}. The real per-path strings are pinned, measured over a socket, in
   * {@link ContentTypeWireIntegrationTest}. Do not re-add an identity assertion here.
   */
  @Test
  void noToken_returns401_exactFourKeyEnvelope() throws Exception {
    String body =
        mockMvc
            .perform(get(UNKNOWN_PAYMENT_PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonShape.keysOf(body))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    assertThat(JsonPath.<String>read(body, "$.error")).isEqualTo("UNAUTHORIZED");
    assertThat(JsonPath.<String>read(body, "$.message")).isEqualTo("Authentication required");
    assertThat(JsonPath.<String>read(body, "$.path")).isEqualTo(UNKNOWN_PAYMENT_PATH);
    JsonShape.assertIso8601Utc(body, "timestamp");
  }

  /**
   * AC-5.5. The INBOUND half of the casing contract: a camelCase body must fail LOUDLY, never bind
   * by accident. The status and the code are pinned exactly — "some 4xx" would be satisfied by the
   * 422 an accidental partial bind produces further down the pipeline.
   *
   * <p><b>What actually produces this 400, measured rather than assumed.</b> Not unknown-property
   * rejection: {@code @JsonIgnoreProperties(ignoreUnknown = false)} on {@code CreatePaymentRequest}
   * is the annotation DEFAULT, which defers to {@code FAIL_ON_UNKNOWN_PROPERTIES} — Boot disables
   * that and no yml re-enables it. So {@code orderId} and {@code paymentMethodToken} are silently
   * dropped, {@code orderId} stays null, and {@code @NotNull} is what rejects the request. That
   * distinction is written down because it decides what a future change may safely rely on: if
   * unknown-property rejection is ever wanted as a real guarantee, it needs its own criterion AND
   * its own config, not an annotation that currently means nothing here.
   */
  @Test
  void camelCaseInboundBody_returns400_validationError() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-camel-inbound")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"orderId":"%s","paymentMethodToken":"pm_valid_token"}
                        """
                            .formatted(UUID.randomUUID())))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonShape.keysOf(body))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    assertThat(JsonPath.<String>read(body, "$.error")).isEqualTo("VALIDATION_ERROR");
    assertThat(JsonPath.<String>read(body, "$.path")).isEqualTo("/api/v1/payments");
    JsonShape.assertIso8601Utc(body, "timestamp");
    assertThat(paymentRepository.count())
        .as("a rejected body must not leave a payment behind")
        .isZero();
  }

  /**
   * AC-5.6 / B12, a security invariant rather than a shape one. The four card-data traps on {@code
   * CreatePaymentRequest} are {@code @Null} fields carrying explicit {@code @JsonProperty} names,
   * so a raw PAN is REJECTED rather than silently ignored — and the rejection must not hand the PAN
   * back in the error body, where it would land in every access log between here and the client.
   *
   * <p>The trap only fires if {@code @JsonProperty("card_number")} still binds the wire key.
   * Jackson 2 leaves explicit names alone unless {@code ALLOW_EXPLICIT_PROPERTY_RENAMING} is on;
   * Jackson 3's behaviour under a naming strategy was unverified before this bump, which is exactly
   * why this is asserted through the WIRE rather than by reading the annotation. If the name were
   * re-mangled, {@code card_number} would become an unknown property, be dropped silently, and this
   * request would be accepted — a 201 here is the regression, not a 500.
   */
  @Test
  void rawCardNumber_returns400_andTheDigitsAreNeverEchoed() throws Exception {
    String pan = "4111111111111111";
    String body =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-raw-pan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"order_id":"%s","payment_method_token":"pm_valid_token","card_number":"%s"}
                        """
                            .formatted(UUID.randomUUID(), pan)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonShape.keysOf(body))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    assertThat(JsonPath.<String>read(body, "$.error")).isEqualTo("VALIDATION_ERROR");
    assertThat(body)
        .as("the PAN must never be echoed — not in the message, not in a field list")
        .doesNotContain(pan);
    assertThat(body)
        .as("nor may the trap field names be reflected back")
        .doesNotContain("pan")
        .doesNotContain("cvv")
        .doesNotContain("cvc");
    assertThat(paymentRepository.count())
        .as("nothing may be persisted for a body carrying raw card data")
        .isZero();
  }

  private String charge(
      String idempotencyKey,
      String paymentMethodToken,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus)
      throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any()))
        .thenReturn(new OrderView(orderId, USER_ID, "PENDING", new BigDecimal("39.98"), "EUR"));

    return mockMvc
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
        .andExpect(expectedStatus)
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
