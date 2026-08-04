package com.ecommerce.user;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the PERMIT side of the security chain: the probe and info endpoints answer an
 * unauthenticated caller with 200, never a 401.
 *
 * <p>Every other security test in this suite asserts a rejection, so a change that made the chain
 * stricter — a new default, or a matcher that stops matching — would be invisible: the deny rows
 * would all still pass while Kubernetes probes and the compose healthchecks started failing. This
 * service is also the only one whose config has no bare {@code /actuator/health} matcher (it relies
 * on {@code /actuator/health/**} covering the parent path), which makes the bare row the most
 * fragile of the set.
 *
 * <p>{@code @TestPropertySource} restores the production {@code management} block, which {@code
 * src/test/resources/application.yml} shadows away — without it exposure falls back to {@code
 * health} only and the probe groups do not exist, so the endpoints would answer 404 for reasons
 * having nothing to do with the security rule under test. The security configuration itself is
 * production code, untouched.
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "management.endpoints.web.exposure.include=health,info,prometheus",
      "management.endpoint.health.probes.enabled=true",
      "management.endpoint.health.group.readiness.include=readinessState,db"
    })
class ActuatorPermitMatrixIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void health_withoutToken_returns200Up() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")));
  }

  @Test
  void healthReadiness_withoutToken_returns200Up() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")));
  }

  @Test
  void healthLiveness_withoutToken_returns200Up() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")));
  }

  @Test
  void info_withoutToken_returns200Json() throws Exception {
    mockMvc
        .perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", containsString("json")));
  }

  /**
   * Control for the four rows above: the permits are scoped, not a chain that has stopped
   * authenticating. Without this a globally disabled security config would turn the whole class
   * green.
   */
  @Test
  void protectedEndpoint_withoutToken_stillReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(content().contentTypeCompatibleWith("application/json"));
  }
}
