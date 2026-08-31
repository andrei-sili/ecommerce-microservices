package com.ecommerce.payment;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.PaymentTransactionRepository;
import com.ecommerce.payment.repository.ProcessedWebhookEventRepository;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared scaffolding for the Slice 5e dual-accept validation suites on the money-moving Payment
 * Service: exercises the REAL security filter chain (full-context MockMvc), captures the {@code
 * jwt.audit} log, and reads the {@code jwt.accepted.tokens} counter via the injected {@link
 * MeterRegistry}. The pinned protected endpoint is {@code GET /api/v1/payments/{id}}; happy rows
 * seed an owned payment directly (so only the GET validates a token — a clean counter delta), abuse
 * rows hit an unknown id (the invalid token 401s before the controller). {@code OrderClient}/{@code
 * OutboxRelay} are mocked with the same override shape as the rest of the suite so the Spring
 * context is reused, not forked.
 */
abstract class AbstractDualAcceptTest extends AbstractIntegrationTest {

  protected static final String BASE = "/api/v1/payments";
  protected static final String OWNER_SUBJECT = "7";
  protected static final long OWNER_ID = 7L;
  protected static final String OTHER_SUBJECT = "99";
  protected static final long OTHER_ID = 99L;

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected MeterRegistry meterRegistry;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private PaymentTransactionRepository transactionRepository;
  @Autowired private ProcessedWebhookEventRepository webhookEventRepository;

  // Mocked to match the cached context of the other @MockitoBean suites (no broker, no outbound
  // HTTP). Neither is invoked here — the security chain and the local ownership guard are fully
  // real.
  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  private Logger auditLogger;
  private ListAppender<ILoggingEvent> auditAppender;

  @BeforeEach
  void resetStateAndCaptureAudit() {
    outboxEventRepository.deleteAll();
    webhookEventRepository.deleteAll();
    transactionRepository.deleteAll();
    paymentRepository.deleteAll();

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

  /** Persists a SUCCEEDED payment owned by {@code userId} and returns its id. */
  protected UUID seedPayment(long userId) {
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            userId,
            new BigDecimal("39.98"),
            "EUR",
            PaymentStatus.SUCCEEDED,
            "sandbox",
            "pm_seed",
            "key-" + UUID.randomUUID());
    return paymentRepository.saveAndFlush(payment).getId();
  }

  /** Happy row: an owned payment fetched with {@code token} returns 200 and its own id. */
  protected void expectPaymentOk(String token) throws Exception {
    UUID paymentId = seedPayment(OWNER_ID);
    mockMvc
        .perform(get(BASE + "/" + paymentId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(paymentId.toString())));
  }

  /**
   * Abuse-row entry point: asserts the pinned 401 envelope AND that the rejection left
   * observability flat — the {@code jwt.accepted.tokens} counter did not move and no {@code
   * jwt.audit} line was emitted. The phase-3 contraction gate is MEASURED from that output, so a
   * refactor that increments on rejection must turn this red, never green.
   */
  protected void expectUnauthorizedEnvelope(String token) throws Exception {
    double acceptedBefore = totalAccepted();
    int auditBefore = auditLineCount();

    String path = BASE + "/" + UUID.randomUUID();
    expectUnauthorizedEnvelope(
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token)), path);

    assertEquals(
        acceptedBefore,
        totalAccepted(),
        0.0001,
        "a rejected token must not increment jwt.accepted.tokens");
    assertEquals(auditBefore, auditLineCount(), "a rejected token must not emit a jwt.audit line");
  }

  protected void expectUnauthorizedEnvelope(ResultActions actions, String path) throws Exception {
    MvcResult result =
        actions
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Authentication required")))
            .andExpect(jsonPath("$.path", is(path)))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>(body.propertyNames());
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
