package com.ecommerce.user;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-context guard for Wave 5c metrics exposure: the Prometheus scrape endpoint is reachable by
 * an unauthenticated scraper (permitAll), yet the change does not loosen auth on business endpoints
 * and the metrics endpoint stays outside the {@code /api/v1} business namespace. Runs against the
 * real security chain + dispatcher + Postgres.
 *
 * <p>Two test-context adjustments reproduce production behaviour that the shared test config
 * suppresses: {@code @AutoConfigureObservability} restores metrics export (Spring Boot's test
 * customizer otherwise forces {@code management.defaults.metrics.export.enabled=false}, hiding the
 * endpoint), and {@code @TestPropertySource} restores the production exposure list ({@code
 * src/test/resources/application.yml} shadows the main file, which defaults exposure to {@code
 * health} only). The security {@code permitAll} rule under test is exercised unchanged.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability
@TestPropertySource(properties = "management.endpoints.web.exposure.include=health,info,prometheus")
class MetricsExposureIntegrationTest extends AbstractIntegrationTest {

  private static final String METRICS_PATH = "/actuator/prometheus";

  @Autowired private MockMvc mockMvc;

  // Criterion 1: no Authorization header -> 200 text/plain with Prometheus exposition text.
  @Test
  void prometheus_withoutAuth_returns200_textPlain_withExpositionText() throws Exception {
    mockMvc
        .perform(get(METRICS_PATH))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", containsString("text/plain")))
        .andExpect(content().string(containsString("# HELP")))
        .andExpect(content().string(containsString("jvm_")));
  }

  // Criterion 2 (abuse): a protected business endpoint stays 401 with no token — auth not loosened.
  @Test
  void protectedEndpoint_withoutToken_stillReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  // Criterion 2 (abuse): a protected business endpoint stays 401 with a malformed/forged token.
  @Test
  void protectedEndpoint_withInvalidToken_stillReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer not.a.jwt"))
        .andExpect(status().isUnauthorized());
  }

  // Criterion 3: the metrics endpoint is NOT under /api/v1 — the permitAll is scoped to /actuator.
  @Test
  void metricsEndpoint_isNotUnderApiV1() throws Exception {
    assertFalse(METRICS_PATH.startsWith("/api/v1"), "metrics endpoint must not live under /api/v1");

    // The same suffix under the business namespace is not the metrics endpoint: no permitAll
    // covers it, so the JWT-less request is rejected (401) and never serves exposition text.
    mockMvc
        .perform(get("/api/v1/actuator/prometheus"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string(not(containsString("# HELP"))));
  }
}
