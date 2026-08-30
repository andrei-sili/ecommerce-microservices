package com.ecommerce.order;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Observability guard (Wave 5c): the Prometheus scrape endpoint is exposed and reachable by an
 * unauthenticated scraper through the REAL security chain, while every JWT-protected business route
 * stays locked down. Runs full-context (real security filter chain + dispatcher + Postgres); the
 * endpoint is never mocked.
 *
 * <p>{@code @AutoConfigureMetrics} is required because {@code @SpringBootTest} disables metrics
 * export (hence the Prometheus registry + scrape endpoint) by default in the test context.
 */
@AutoConfigureMetrics
class PrometheusMetricsIntegrationTest extends AbstractIntegrationTest {

  private static final String PROMETHEUS_PATH = "/actuator/prometheus";

  /** Captured at 3.5.16; the scrape parser is selected from these parameters, not from the type. */
  private static final String PROMETHEUS_CONTENT_TYPE = "text/plain;version=0.0.4;charset=utf-8";

  @Autowired private MockMvc mockMvc;

  // Criterion: GIVEN the app running, WHEN GET /actuator/prometheus with NO auth header,
  // THEN 200 + text/plain (Prometheus exposition format) with real metric families.
  @Test
  void prometheusScrape_noAuthHeader_returns200_textPlain_prometheusText() throws Exception {
    mockMvc
        .perform(get(PROMETHEUS_PATH))
        .andExpect(status().isOk())
        // Micrometer's scrape endpoint negotiates to the classic text exposition format. Pinned
        // EXACTLY (version + charset included): Prometheus selects the parser from these
        // parameters, so a drift to the OpenMetrics content type is a scrape-breaking change that
        // `contentTypeCompatibleWith("text/plain")` cannot see.
        .andExpect(content().contentType(PROMETHEUS_CONTENT_TYPE))
        // Prometheus exposition markers + a metric family that always exists on a JVM app.
        .andExpect(content().string(containsString("# HELP")))
        .andExpect(content().string(containsString("# TYPE")))
        .andExpect(content().string(containsString("jvm_memory_used_bytes")));
  }

  // Criterion (abuse): a protected business route with NO token still gets 401 via the entry point,
  // and the standard error envelope — the metrics permit did not loosen the terminal denyAll.
  @Test
  void protectedRoute_noToken_stillReturns401_withEnvelope() throws Exception {
    mockMvc
        .perform(get("/api/v1/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", is("/api/v1/orders")));
  }

  // Criterion (abuse): a protected business route with a malformed/non-parseable bearer token is
  // left unauthenticated by the JWT filter and still gets 401 — never served as if authenticated.
  // Pins the FULL 4-field envelope (not just status + error), like the other abuse rows.
  @Test
  void protectedRoute_invalidToken_stillReturns401_withFullEnvelope() throws Exception {
    mockMvc
        .perform(get("/api/v1/orders").header("Authorization", "Bearer not-a-real-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.message", is("Authentication required")))
        .andExpect(jsonPath("$.path", is("/api/v1/orders")))
        .andExpect(jsonPath("$.timestamp", notNullValue()));
  }

  // Criterion: /actuator/prometheus is an operational endpoint, NOT under /api/v1 — so it is exempt
  // from the versioned-API auth rule. Prove the boundary: the SAME path under /api/v1 is unmapped
  // AND still auth-gated (401 without a token), i.e. the metrics permit is scoped to /actuator
  // only.
  @Test
  void prometheusPath_isNotUnderApiV1_andApiV1VariantStaysAuthGated() throws Exception {
    assertFalse(
        PROMETHEUS_PATH.startsWith("/api/v1"), "metrics endpoint must not live under /api/v1");

    mockMvc
        .perform(get("/api/v1/actuator/prometheus"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
  }

  // Control: an authenticated scrape also succeeds (permitAll does not block a token-carrying
  // call).
  @Test
  void prometheusScrape_withValidToken_alsoReturns200() throws Exception {
    String token = TestJwt.bearer(TestJwt.token("7", List.of("USER")));
    mockMvc
        .perform(get(PROMETHEUS_PATH).header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", PROMETHEUS_CONTENT_TYPE));
  }
}
