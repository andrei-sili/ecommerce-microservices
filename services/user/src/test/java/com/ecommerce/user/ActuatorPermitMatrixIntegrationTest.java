package com.ecommerce.user;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the PERMIT side of the security chain — the probe and info endpoints answer an
 * unauthenticated caller with 200, never a 401 — and the EXACT body each one ships.
 *
 * <p>Every other security test in this suite asserts a rejection, so a change that made the chain
 * stricter would be invisible: the deny rows would all still pass while Kubernetes probes and the
 * compose healthchecks started failing. This service is also the only one whose config has no bare
 * {@code /actuator/health} matcher (it relies on {@code /actuator/health/**} covering the parent
 * path), which makes the bare row the most fragile of the set.
 *
 * <p>The bodies are asserted whole, not through {@code $.status}. A subset assertion cannot see the
 * disclosure this pin exists to prevent: {@code management.endpoint.health.show-details} is set
 * nowhere in this repo, so production runs the framework default {@code never}; if that default
 * flips, {@code components} appears and the DB product and version leak to any unauthenticated
 * caller on the pod network — while {@code $.status} stays {@code "UP"} and stays green.
 *
 * <p>{@code @TestPropertySource} restores the production {@code management} block, which {@code
 * src/test/resources/application.yml} shadows away — without it exposure falls back to {@code
 * health} only and the probe groups do not exist, so the endpoints would answer 404 for reasons
 * having nothing to do with the rule under test. It replays the shipped values verbatim and adds
 * nothing: notably it does NOT set {@code show-details}, so these bodies are the default-rendered
 * ones. The security configuration itself is production code, untouched.
 */
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "management.endpoints.web.exposure.include=health,info,prometheus",
      "management.endpoint.health.probes.enabled=true",
      "management.endpoint.health.group.readiness.include=readinessState,db"
    })
class ActuatorPermitMatrixIntegrationTest extends AbstractIntegrationTest {

  /** Actuator negotiates its own vendor type; a drift to plain {@code application/json} matters. */
  private static final String ACTUATOR_JSON = "application/vnd.spring-boot.actuator.v3+json";

  private static final String PROBE_BODY = "{\"status\":\"UP\"}";

  @Autowired private MockMvc mockMvc;

  /**
   * The root aggregate. It carries {@code groups} because {@code probes.enabled} plus the explicit
   * readiness group make Boot render {@code SystemHealth}, whose {@code groups} member is not gated
   * by {@code show-details} — measured, not assumed. Neither real consumer is affected (the compose
   * healthcheck greps the substring {@code "status":"UP"}, Kong reads only the status code), but
   * the byte-exact form is what makes an added {@code components} key visible here.
   */
  @Test
  void health_withoutToken_returns200_withExactShippedBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"));
  }

  @Test
  void healthReadiness_withoutToken_returns200_withExactShippedBody() throws Exception {
    byte[] body =
        mockMvc
            .perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(ACTUATOR_JSON))
            .andExpect(content().string(PROBE_BODY))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertEquals(
        15,
        body.length,
        "shipped readiness body must stay 15 bytes: " + new String(body, StandardCharsets.UTF_8));
  }

  @Test
  void healthLiveness_withoutToken_returns200_withExactShippedBody() throws Exception {
    byte[] body =
        mockMvc
            .perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(ACTUATOR_JSON))
            .andExpect(content().string(PROBE_BODY))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertEquals(
        15,
        body.length,
        "shipped liveness body must stay 15 bytes: " + new String(body, StandardCharsets.UTF_8));
  }

  /**
   * Empty today because no info contributor is enabled. Boot 4.1 adds {@code process.*} keys to
   * this endpoint by default, which is exactly the kind of unannounced disclosure the exact body —
   * and not {@code status().isOk()} — is here to surface.
   */
  @Test
  void info_withoutToken_returns200_withExactShippedBody() throws Exception {
    mockMvc
        .perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{}"));
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
        .andExpect(content().contentType("application/json"));
  }
}
