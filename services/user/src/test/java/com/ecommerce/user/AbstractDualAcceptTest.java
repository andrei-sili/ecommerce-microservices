package com.ecommerce.user;

import static com.ecommerce.user.support.ErrorEnvelopes.assertJsonNotProblem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.support.JwtTestKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shared scaffolding for the Slice 5e dual-accept validation suites: exercises the REAL filter
 * chain (full-context MockMvc), captures the {@code jwt.audit} log, and reads the {@code
 * jwt.accepted.tokens} counter via the injected {@link MeterRegistry}. Concrete subclasses pin the
 * accepted-algs configuration and assert the matrix rows.
 */
@AutoConfigureMockMvc
abstract class AbstractDualAcceptTest extends AbstractIntegrationTest {

  protected static final String PROFILE_PATH = "/api/v1/users/me";
  private static final String VALID_PASSWORD = "Sup3rSecret12";
  private static final String CONTROL_EMAIL = "observability-control@example.com";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected MeterRegistry meterRegistry;
  @Autowired private UserRepository userRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private Logger auditLogger;
  private ListAppender<ILoggingEvent> auditAppender;

  @BeforeEach
  void resetStateAndCaptureAudit() {
    refreshTokenRepository.deleteAll();
    outboxEventRepository.deleteAll();
    userRepository.deleteAll();

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

  protected long registerUser(String email, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"password\":\""
                            + VALID_PASSWORD
                            + "\",\"name\":\""
                            + name
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }

  protected void expectProfileOk(String token, String expectedEmail) throws Exception {
    mockMvc
        .perform(get(PROFILE_PATH).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email", is(expectedEmail)));
  }

  /**
   * Abuse-row entry point: asserts the pinned 401 envelope AND that the rejection left
   * observability flat — the {@code jwt.accepted.tokens} counter did not move and no {@code
   * jwt.audit} line was emitted. The phase-3 contraction gate is MEASURED from that output, so a
   * refactor that increments on rejection must turn this red, never green.
   *
   * <p>The two "did not move" assertions are worthless on their own: they are equally true of a
   * build where the counter and the audit logger were deleted outright, or where the meter was
   * renamed and {@code find("jwt.accepted.tokens")} now matches nothing — a signal that is always
   * zero never moves. So each abuse row first drives a token this context DOES accept and requires
   * the counter to move by exactly 1.0 and the audit log by exactly one line. The positive control
   * lives in the same method deliberately: split across two tests, the negative half would keep
   * passing on its own the day the positive half broke.
   */
  protected void expectUnauthorizedEnvelope(String token) throws Exception {
    assertObservabilityCanMove();

    double acceptedBefore = totalAccepted();
    int auditBefore = auditLineCount();

    expectUnauthorizedEnvelope(
        mockMvc.perform(get(PROFILE_PATH).header("Authorization", "Bearer " + token)));

    assertEquals(
        acceptedBefore,
        totalAccepted(),
        0.0001,
        "a rejected token must not increment jwt.accepted.tokens");
    assertEquals(auditBefore, auditLineCount(), "a rejected token must not emit a jwt.audit line");
  }

  /** Positive half of the control: an accepted token moves both signals by exactly one. */
  private void assertObservabilityCanMove() throws Exception {
    long controlUserId = registerUser(CONTROL_EMAIL, "Control");
    double acceptedBefore = totalAccepted();
    int auditBefore = auditLineCount();

    expectProfileOk(acceptedToken(controlUserId), CONTROL_EMAIL);

    assertEquals(
        acceptedBefore + 1.0,
        totalAccepted(),
        0.0001,
        "an accepted token must increment jwt.accepted.tokens — a signal stuck at zero cannot"
            + " demonstrate anything about the rejection that follows");
    assertEquals(
        auditBefore + 1,
        auditLineCount(),
        "an accepted token must emit exactly one jwt.audit line");
    assertAudited(expectedAuditLine());
  }

  /**
   * A token this context's allowlist accepts. RS256 is the phase-3 posture and the default; {@link
   * Hs256OnlyValidationIntegrationTest} pins the rollback direction and overrides it.
   */
  protected String acceptedToken(long userId) {
    return JwtTestKeys.mintRs256(userId, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);
  }

  /** The audit line {@link #acceptedToken} must produce — alg and kid are part of the contract. */
  protected String expectedAuditLine() {
    return "JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_A;
  }

  protected void expectUnauthorizedEnvelope(ResultActions actions) throws Exception {
    MvcResult result =
        actions
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Authentication required")))
            .andExpect(jsonPath("$.path", is(PROFILE_PATH)))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andReturn();

    assertJsonNotProblem(result);
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>();
    body.fieldNames().forEachRemaining(keys::add);
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
