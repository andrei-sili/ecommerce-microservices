package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the HTTP-level {@code 401} contract for the money-moving Payment Service. The security
 * filter chain must reject an <b>expired</b> token and any <b>non-Bearer</b> authorization scheme
 * with the standard {@code {error, message, timestamp, path}} envelope, and — on the {@code POST
 * /payments} money path — do so <b>before</b> any controller-level {@code 400} (filter-chain
 * precedence). This behaviour was proven live but nothing bound it in CI; a regression in scheme
 * parsing, expiry enforcement, or envelope rendering would have stayed green.
 *
 * <p>Runs against the real {@code FilterChainProxy} + {@code RestAuthenticationEntryPoint} +
 * Postgres — no security bean is mocked. {@code OrderClient}/{@code OutboxRelay} are mocked with
 * the same override shape as {@link FrameworkErrorMappingIntegrationTest} so the Spring context is
 * reused, not forked. The webhook ({@code permitAll}, signature-authenticated) is a different seam
 * and is intentionally untouched.
 */
class TokenAuth401IntegrationTest extends AbstractIntegrationTest {

  private static final String VALID_TOKEN = TestJwt.token("7", List.of("USER"));
  private static final String PROBE_PATH = "/api/v1/payments/" + UUID.randomUUID();

  @Autowired private MockMvc mockMvc;
  @Autowired private PaymentRepository paymentRepository;

  // Same override shape as PaymentIntegrationTest/FrameworkErrorMappingIntegrationTest so this
  // class
  // reuses the cached context instead of forking a new one. No security bean is mocked.
  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  // Criterion 1 — anti-vacuous control: a freshly minted VALID token clears the security layer and
  // reaches business logic (404, not 401), so the 401s below cannot be a stale/wrong-secret
  // artefact.
  // 404 (not 200) also honours the contract's no-existence-leak rule for an unknown payment.
  @Test
  void validToken_getUnknownPayment_returns404_notUnauthorized() throws Exception {
    mockMvc
        .perform(get(PROBE_PATH).header("Authorization", TestJwt.bearer(VALID_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
  }

  // Criterion 2 — expired token: exact 401 + full standard envelope, no leak of the cause.
  @Test
  void expiredToken_returns401_fullEnvelope() throws Exception {
    String body =
        mockMvc
            .perform(
                get(PROBE_PATH)
                    .header(
                        "Authorization",
                        TestJwt.bearer(TestJwt.expiredToken("7", List.of("USER")))))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Authentication required"))
            .andExpect(jsonPath("$.path").value(PROBE_PATH))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertParsableIso8601Timestamp(body);
  }

  // Criterion 3 — non-Bearer scheme: every variant carries a functional VALID credential; only the
  // scheme is wrong. Each returns 401 with the full envelope (strict, case-sensitive "Bearer "
  // prefix
  // is a deliberate pin per contract — lowercase/glued/raw are all rejected).
  @ParameterizedTest(name = "{0}")
  @MethodSource("nonBearerSchemes")
  void nonBearerScheme_returns401_fullEnvelope(String label, String authorizationHeader)
      throws Exception {
    String body =
        mockMvc
            .perform(get(PROBE_PATH).header("Authorization", authorizationHeader))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Authentication required"))
            .andExpect(jsonPath("$.path").value(PROBE_PATH))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertParsableIso8601Timestamp(body);
  }

  static Stream<Arguments> nonBearerSchemes() {
    String basicCredential =
        Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8));
    return Stream.of(
        Arguments.of("Basic scheme", "Basic " + basicCredential),
        Arguments.of("Token scheme, valid jwt", "Token " + VALID_TOKEN),
        Arguments.of("lowercase bearer, valid jwt", "bearer " + VALID_TOKEN),
        Arguments.of("Bearer glued without space, valid jwt", "Bearer" + VALID_TOKEN),
        Arguments.of("raw token, no scheme", VALID_TOKEN));
  }

  // Criterion 4 — precedence on the money path: an expired token on POST /payments with NO
  // Idempotency-Key and NO body returns 401 (not the controller's 400 for the missing header),
  // because the filter chain runs before the dispatcher. Nothing is persisted.
  @Test
  void expiredToken_postPayments_returns401_beforeControllerValidation_nothingPersisted()
      throws Exception {
    long countBefore = paymentRepository.count();

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header(
                    "Authorization", TestJwt.bearer(TestJwt.expiredToken("7", List.of("USER")))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

    assertThat(paymentRepository.count()).isEqualTo(countBefore);
  }

  /**
   * A9: {@code path} is echoed from {@code request.getRequestURI()}, which is attacker-controlled
   * and carries the raw percent-encoded bytes rather than a decoded string. Boot 4.1 ships Tomcat
   * 11 / Servlet 6.1, so a container that starts decoding (or re-encoding) the segment changes the
   * envelope for every non-ASCII URL — a drift no ASCII path in this suite can expose.
   */
  @Test
  void nonAsciiPathSegment_returns401_andEchoesTheUriPercentEncoded() throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/payments/caf\u00e9"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Authentication required"))
            .andExpect(jsonPath("$.path").value("/api/v1/payments/caf%C3%A9"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertParsableIso8601Timestamp(body);
  }

  private static void assertParsableIso8601Timestamp(String body) {
    String timestamp = JsonPath.read(body, "$.timestamp");
    assertThat(timestamp).isNotNull();
    assertThatCode(() -> Instant.parse(timestamp)).doesNotThrowAnyException();
  }
}
