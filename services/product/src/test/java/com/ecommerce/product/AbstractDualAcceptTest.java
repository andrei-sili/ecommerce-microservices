package com.ecommerce.product;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.product.model.Category;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.repository.StockReservationRepository;
import com.ecommerce.product.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared scaffolding for the Slice 5e dual-accept validation suites: exercises the REAL filter
 * chain (full-context MockMvc), captures the {@code jwt.audit} log, and reads the {@code
 * jwt.accepted.tokens} counter via the injected {@link MeterRegistry}. The pinned protected
 * endpoint is {@code POST /api/v1/products} (ADMIN write); a category is seeded per test so the
 * happy write returns 201.
 */
abstract class AbstractDualAcceptTest extends AbstractIntegrationTest {

  protected static final String PRODUCT_PATH = "/api/v1/products";
  private static final AtomicInteger SKU_SEQ = new AtomicInteger();

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected MeterRegistry meterRegistry;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private StockReservationRepository stockReservationRepository;

  private long categoryId;
  private Logger auditLogger;
  private ListAppender<ILoggingEvent> auditAppender;

  @BeforeEach
  void resetStateAndCaptureAudit() {
    cleanCatalog();
    categoryId = categoryRepository.save(new Category("Apparel", "apparel")).getId();

    auditLogger = (Logger) LoggerFactory.getLogger("jwt.audit");
    auditAppender = new ListAppender<>();
    auditAppender.start();
    auditLogger.addAppender(auditAppender);
  }

  @AfterEach
  void detachAuditAndCleanup() {
    if (auditLogger != null && auditAppender != null) {
      auditLogger.detachAppender(auditAppender);
    }
    // The context (and its Postgres) is shared across suites; leave the catalog empty so other
    // classes (e.g. ProductCatalogIT's total_elements assertions) are not contaminated.
    cleanCatalog();
  }

  private void cleanCatalog() {
    stockReservationRepository.deleteAll();
    productRepository.deleteAll();
    categoryRepository.deleteAll();
  }

  /** Posts a valid product as the ADMIN caller and asserts the 201 resource body. */
  protected void expectCreated(String token) throws Exception {
    String sku = "DUAL-" + SKU_SEQ.incrementAndGet();
    mockMvc
        .perform(
            post(PRODUCT_PATH)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(productBody(sku)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sku", is(sku)))
        .andExpect(jsonPath("$.available", is(true)));
  }

  private String productBody(String sku) {
    return "{\"sku\":\""
        + sku
        + "\",\"name\":\"Tee\",\"price\":19.99,\"currency\":\"EUR\",\"category_id\":"
        + categoryId
        + ",\"stock_quantity\":10}";
  }

  protected ResultActions postProduct(String token) throws Exception {
    return postProductRaw("Bearer " + token);
  }

  /** POST with a caller-supplied raw {@code Authorization} header (for non-Bearer scheme rows). */
  private ResultActions postProductRaw(String authorizationHeaderValue) throws Exception {
    return mockMvc.perform(
        post(PRODUCT_PATH)
            .header("Authorization", authorizationHeaderValue)
            .contentType(MediaType.APPLICATION_JSON)
            .content(productBody("DUAL-" + SKU_SEQ.incrementAndGet())));
  }

  /**
   * Non-Bearer-scheme abuse row: sends a raw {@code Authorization} header, asserts the pinned 401
   * envelope AND flat observability (counter unmoved, no {@code jwt.audit} line) — the filter must
   * fail closed on any scheme that is not the strict, case-sensitive {@code "Bearer "} prefix.
   */
  protected void expectUnauthorizedRawHeader(String authorizationHeaderValue) throws Exception {
    double acceptedBefore = totalAccepted();
    int auditBefore = auditLineCount();

    expectUnauthorizedEnvelope(postProductRaw(authorizationHeaderValue));

    assertEquals(
        acceptedBefore,
        totalAccepted(),
        0.0001,
        "a rejected scheme must not increment jwt.accepted.tokens");
    assertEquals(auditBefore, auditLineCount(), "a rejected scheme must not emit a jwt.audit line");
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

    expectUnauthorizedEnvelope(postProduct(token));

    assertEquals(
        acceptedBefore,
        totalAccepted(),
        0.0001,
        "a rejected token must not increment jwt.accepted.tokens");
    assertEquals(auditBefore, auditLineCount(), "a rejected token must not emit a jwt.audit line");
  }

  /**
   * The {@code Content-Type} is matched EXACTLY, not with {@code contentTypeCompatibleWith}, which
   * ignores the charset parameter and also accepts {@code application/problem+json}. Exact equality
   * is the stronger form of both guards: it fails on a charset appearing or disappearing (the
   * Tomcat 11 / Servlet 6.1 risk on this {@code response.getWriter()} path) and on any migration to
   * RFC-7807 problem details.
   */
  protected void expectUnauthorizedEnvelope(ResultActions actions) throws Exception {
    MvcResult result =
        actions
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Authentication required")))
            .andExpect(jsonPath("$.path", is(PRODUCT_PATH)))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andReturn();
    assertFourFieldEnvelope(result);
  }

  /**
   * Authorization row: a VALID token whose roles lack ADMIN → 403 envelope (token still accepted).
   */
  protected void expectForbiddenEnvelope(String token) throws Exception {
    MvcResult result =
        postProduct(token)
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("FORBIDDEN")))
            .andExpect(jsonPath("$.message", is("Insufficient permissions")))
            .andExpect(jsonPath("$.path", is(PRODUCT_PATH)))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andReturn();
    assertFourFieldEnvelope(result);
  }

  private void assertFourFieldEnvelope(MvcResult result) throws Exception {
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>();
    body.propertyNames().forEach(keys::add);
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "error envelope must expose exactly the four contract keys");
  }

  protected void expectPublicGetOk(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isOk());
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
