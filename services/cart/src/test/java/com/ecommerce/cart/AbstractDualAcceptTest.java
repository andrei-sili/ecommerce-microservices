package com.ecommerce.cart;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.ecommerce.cart.support.JwtTestKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared scaffolding for the Slice 5e dual-accept validation suites: exercises the REAL filter
 * chain (full-context MockMvc against a real Postgres), captures the {@code jwt.audit} log, and
 * reads the {@code jwt.accepted.tokens} counter via the injected {@link MeterRegistry}. The
 * canonical protected endpoint is {@code GET /api/v1/cart} — self-scoped on the JWT {@code sub}, so
 * there is no cross-user path to exercise. Concrete subclasses pin the accepted-algs configuration
 * and assert the matrix rows.
 */
abstract class AbstractDualAcceptTest extends AbstractIntegrationTest {

  protected static final String CART_PATH = "/api/v1/cart";

  /**
   * Captured on 3.5.16: the entry point sets {@code application/json} with no charset parameter.
   */
  private static final String UNAUTHORIZED_CONTENT_TYPE = "application/json";

  /** Distinct from every suite's business subject, so the control never shares a cart row. */
  protected static final long CONTROL_USER_ID = 4242L;

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected MeterRegistry meterRegistry;
  @Autowired private CartRepository cartRepository;

  private Logger auditLogger;
  private ListAppender<ILoggingEvent> auditAppender;

  @BeforeEach
  void resetStateAndCaptureAudit() {
    cartRepository.deleteAll();

    auditLogger = (Logger) LoggerFactory.getLogger("jwt.audit");
    auditAppender = new ListAppender<>();
    auditAppender.start();
    auditLogger.addAppender(auditAppender);
  }

  @AfterEach
  void detachAudit() {
    if (auditLogger != null && auditAppender != null) {
      auditLogger.detachAppender(auditAppender);
    }
  }

  /** Happy-row entry point: the caller's own (auto-created) cart is returned, scoped to the sub. */
  protected void expectCartOk(String token, long expectedUserId) throws Exception {
    mockMvc
        .perform(get(CART_PATH).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id", is((int) expectedUserId)));
  }

  /**
   * Abuse-row entry point: asserts the pinned 401 envelope AND that the rejection left
   * observability flat — the {@code jwt.accepted.tokens} counter did not move and no {@code
   * jwt.audit} line was emitted. The phase-3 contraction gate is MEASURED from that output, so a
   * refactor that increments on rejection must turn this red, never green.
   */
  protected void expectUnauthorizedEnvelope(String token) throws Exception {
    performUnauthorizedFlat(get(CART_PATH).header("Authorization", "Bearer " + token));
  }

  /**
   * Abuse-row for a RAW Authorization header (non-Bearer schemes) — envelope + flat observability.
   */
  protected void expectUnauthorizedForHeader(String authorizationHeaderValue) throws Exception {
    performUnauthorizedFlat(get(CART_PATH).header("Authorization", authorizationHeaderValue));
  }

  /** Abuse-row for a request with NO Authorization header — envelope + flat observability. */
  protected void expectUnauthorizedWithoutAuth() throws Exception {
    performUnauthorizedFlat(get(CART_PATH));
  }

  private void performUnauthorizedFlat(MockHttpServletRequestBuilder request) throws Exception {
    double acceptedBefore = totalAccepted();
    int auditBefore = auditLineCount();

    expectUnauthorizedEnvelope(mockMvc.perform(request));

    assertEquals(
        acceptedBefore,
        totalAccepted(),
        0.0001,
        "a rejected token must not increment jwt.accepted.tokens");
    assertEquals(auditBefore, auditLineCount(), "a rejected token must not emit a jwt.audit line");

    // F10 positive control, in the SAME method: "did not move" is free if the counter and the audit
    // logger cannot move at all. The metrics-annotation swap, Micrometer 1.16->1.17 and the context
    // forks these suites create are exactly the substrate the migration changes, so a run where
    // neither direction moves must FAIL rather than read as a clean negative.
    expectCartOk(acceptedToken(), CONTROL_USER_ID);

    assertEquals(
        acceptedBefore + 1,
        totalAccepted(),
        0.0001,
        "an accepted token must increment jwt.accepted.tokens by exactly 1");
    assertEquals(
        auditBefore + 1,
        auditLineCount(),
        "an accepted token must emit exactly one jwt.audit line");
  }

  /**
   * A token the suite's own accepted-algs configuration must accept. RS256 is the shipped posture;
   * the HS256-rollback suite overrides this so its control is not itself a rejection.
   */
  protected String acceptedToken() {
    return JwtTestKeys.mintRs256(CONTROL_USER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);
  }

  protected void expectUnauthorizedEnvelope(ResultActions actions) throws Exception {
    expectUnauthorizedEnvelopeForPath(actions, CART_PATH);
  }

  /**
   * The 401 envelope, with {@code path} asserted against the caller-supplied URI so the row can
   * also be driven on a non-ASCII path.
   *
   * <p>The media type is pinned as the EXACT captured string (A9). {@code
   * contentTypeCompatibleWith} ignores media-type parameters, so a charset appearing on the entry
   * point's {@code response.getWriter()} path — Boot 4.1 ships Tomcat 11 / Servlet 6.1, which is a
   * different container charset path than {@code getOutputStream()} — would satisfy it while
   * changing the bytes every client sees.
   */
  protected void expectUnauthorizedEnvelopeForPath(ResultActions actions, String expectedPath)
      throws Exception {
    MvcResult result =
        actions
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(UNAUTHORIZED_CONTENT_TYPE))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, UNAUTHORIZED_CONTENT_TYPE))
            .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Authentication required")))
            .andExpect(jsonPath("$.path", is(expectedPath)))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>();
    body.propertyNames().forEach(keys::add);
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "401 envelope must expose exactly the four contract keys");
  }

  protected double counterCount(String alg, String kid) {
    Counter counter =
        meterRegistry.find("jwt.accepted.tokens").tag("alg", alg).tag("kid", kid).counter();
    return counter == null ? 0.0 : counter.count();
  }

  /** Total acceptances across all tag combinations — the phase-3 gate's raw signal. */
  protected double totalAccepted() {
    return meterRegistry.find("jwt.accepted.tokens").counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  /** Count of captured {@code jwt.audit} lines (that logger emits only the acceptance line). */
  protected int auditLineCount() {
    return auditAppender.list.size();
  }

  protected void assertAudited(String expectedLine) {
    assertTrue(
        auditAppender.list.stream().anyMatch(e -> e.getFormattedMessage().equals(expectedLine)),
        "expected a jwt.audit line: " + expectedLine);
  }
}
