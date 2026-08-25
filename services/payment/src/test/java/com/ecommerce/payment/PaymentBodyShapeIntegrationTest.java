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
