package com.ecommerce.payment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.ecommerce.payment.support.TestJwt;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-context guard for framework/dispatcher error mapping in the (money-sensitive) Payment
 * Service: unmapped path, wrong method, bad media type and unreadable body must render the standard
 * {@code {error, message, timestamp, path}} envelope with the correct 4xx status — never a 500 —
 * while security (401), validation (400) and the happy GET stay intact. Runs against the real
 * security chain + dispatcher + Postgres; OrderClient/OutboxRelay are mocked exactly as the rest of
 * the suite so no broker is needed. This class only exercises framework-level errors — the
 * money/idempotency/webhook business paths live in {@link PaymentIntegrationTest} and stay
 * untouched.
 */
class FrameworkErrorMappingIntegrationTest extends AbstractIntegrationTest {

  private static final String USER_SUBJECT = "7";
  private static final Long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token(USER_SUBJECT, List.of("USER")));

  @Autowired private MockMvc mockMvc;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private PaymentTransactionRepository transactionRepository;
  @Autowired private ProcessedWebhookEventRepository webhookEventRepository;

  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    webhookEventRepository.deleteAll();
    transactionRepository.deleteAll();
    paymentRepository.deleteAll();
  }

  // 1. Unmapped sibling path with a valid token -> 404 RESOURCE_NOT_FOUND, full envelope, JSON.
  @Test
  void unmappedPath_returns404_withEnvelope_andJsonContentType_noLeak() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/payments-typo").header("Authorization", USER))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
            .andExpect(jsonPath("$.message", is("Resource not found")))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.path", is("/api/v1/payments-typo")))
            // error envelope is application/json, NOT application/problem+json.
            .andExpect(header().string("Content-Type", containsString("application/json")))
            .andReturn();

    String contentType = result.getResponse().getContentType();
    assertFalse(
        contentType != null && contentType.contains("problem"),
        "framework error must not use application/problem+json, was: " + contentType);
    assertNoLeak(result);
  }

  // 2. Unmapped sub-path under a real item route -> 404 RESOURCE_NOT_FOUND (message must not echo
  // the path).
  @Test
  void unmappedSubPath_returns404_withEnvelope_andNoPathLeak() throws Exception {
    String path = "/api/v1/payments/" + UUID.randomUUID() + "/foo";

    MvcResult result =
        mockMvc
            .perform(get(path).header("Authorization", USER))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
            .andExpect(jsonPath("$.message", is("Resource not found")))
            .andExpect(jsonPath("$.path", is(path)))
            .andReturn();

    assertNoLeak(result);
  }

  // 3. Wrong HTTP method on the mapped item route -> 405 + Allow header listing GET.
  @Test
  void wrongMethod_returns405_withAllowHeader() throws Exception {
    String path = "/api/v1/payments/" + UUID.randomUUID();

    MvcResult result =
        mockMvc
            .perform(delete(path).header("Authorization", USER))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.error", is("METHOD_NOT_ALLOWED")))
            .andExpect(jsonPath("$.path", is(path)))
            .andExpect(header().string("Allow", containsString("GET")))
            .andReturn();

    assertNoLeak(result);
  }

  // 4. Unsupported Content-Type on POST /payments -> 415 + Accept header (Idempotency-Key supplied
  // so the media-type error is the only fault).
  @Test
  void unsupportedMediaType_returns415_withAcceptHeader() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-415")
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("just some plain text"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error", is("UNSUPPORTED_MEDIA_TYPE")))
            .andExpect(jsonPath("$.path", is("/api/v1/payments")))
            .andExpect(header().exists("Accept"))
            .andReturn();

    assertNoLeak(result);
  }

  // 5. Malformed JSON body (regression guard) -> 400 MALFORMED_REQUEST.
  @Test
  void malformedJson_returns400_malformedRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-malformed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("MALFORMED_REQUEST")))
            .andExpect(jsonPath("$.path", is("/api/v1/payments")))
            .andReturn();

    assertNoLeak(result);
  }

  // 6. Bean-Validation failure (regression guard) -> 400 VALIDATION_ERROR naming the field(s).
  // Payment encodes violations into the envelope `message` (not a `fields` array).
  @Test
  void invalidBody_returns400_validationError_namingField() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-invalid")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"payment_method_token\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
            .andExpect(jsonPath("$.path", is("/api/v1/payments")))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertTrue(
        body.contains("orderId") || body.contains("paymentMethodToken"),
        "validation envelope must name the offending field, was: " + body);
    assertNoLeak(result);
  }

  // 7. Security regression guard: no Authorization header -> 401 UNAUTHORIZED (entry point).
  @Test
  void noToken_returns401_unauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/payments/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", notNullValue()));
  }

  // 8. Happy-path control: a real GET with the owner's token still returns 200 (funnel did not
  // break the success path).
  @Test
  void mappedRoute_withValidToken_returns200() throws Exception {
    String paymentId = createSucceededPayment("key-happy-get");

    mockMvc
        .perform(get("/api/v1/payments/" + paymentId).header("Authorization", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(paymentId)))
        .andExpect(jsonPath("$.status", is("SUCCEEDED")));
  }

  /** Creates a SUCCEEDED payment owned by USER and returns its id. */
  private String createSucceededPayment(String idempotencyKey) throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderClient.getOrder(any(), any()))
        .thenReturn(new OrderView(orderId, USER_ID, "PENDING", new BigDecimal("39.98"), "EUR"));

    String response =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", idempotencyKey)
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
    return com.jayway.jsonpath.JsonPath.read(response, "$.id");
  }

  /** No error body may leak the static-resource message, a stack trace or other internals. */
  private void assertNoLeak(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    for (String forbidden :
        new String[] {
          "No static resource",
          "nested exception",
          "Exception",
          "\tat ",
          "java.lang.",
          "org.springframework."
        }) {
      assertFalse(
          body.contains(forbidden), "error body leaked internals (\"" + forbidden + "\"): " + body);
    }
  }
}
