package com.ecommerce.cart;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-context guard for the Prometheus scrape endpoint against the real security chain: the
 * unauthenticated scraper reaches {@code /actuator/prometheus} (200, Prometheus text) because the
 * chain explicitly permits that single path, while every protected {@code /api/v1} route stays
 * behind auth. The endpoint lives off the {@code /api/v1} business-versioning namespace on purpose
 * (ClusterIP-internal, never on the Kong edge).
 *
 * <p>{@code @AutoConfigureObservability} re-enables the metrics export the Spring Boot test harness
 * disables by default (via {@code management.defaults.metrics.export.enabled=false}); the running
 * app has no such override, so the endpoint is exposed there without any flag.
 */
@AutoConfigureObservability(tracing = false)
class MetricsEndpointIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  // 1. Anonymous scraper (no Authorization header) -> 200, text/plain, Prometheus exposition text.
  @Test
  void prometheusEndpoint_withoutAuth_returns200_textPlain_prometheusText() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/plain")))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("# HELP"), "expected Prometheus HELP comments, was: " + body);
    assertTrue(body.contains("# TYPE"), "expected Prometheus TYPE comments, was: " + body);
    assertTrue(
        body.contains("jvm_memory_used_bytes"),
        "expected a JVM metric family in the scrape, was: " + body);
  }

  // 2a. A protected route with NO token is still rejected -> the permitAll did not open the chain.
  @Test
  void protectedEndpoint_withoutToken_stillUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/cart"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart")));
  }

  // 2b. A protected route with a malformed (non-JWT) bearer token is still rejected -> 401.
  @Test
  void protectedEndpoint_withInvalidToken_stillUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", "Bearer not-a-real-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart")));
  }

  // 3. The scrape endpoint is NOT under /api/v1: the versioned equivalent is not exposed and, being
  // under /api/v1/**, is governed by the authenticated() rule -> anonymous access is 401, proving
  // the permitAll matched only the exact /actuator/prometheus path off the business namespace.
  @Test
  void prometheusEndpoint_notUnderApiV1_versionedPathRequiresAuth() throws Exception {
    mockMvc
        .perform(get("/api/v1/actuator/prometheus"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", is("/api/v1/actuator/prometheus")));
  }
}
