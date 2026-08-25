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
 * unauthenticated caller with 200, never a 401 — and the exact body each one renders.
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
 * flips, {@code components} appears and the DB product, version and host disk paths go to any
 * unauthenticated caller on the pod network — while {@code $.status} stays {@code "UP"} and stays
 * green.
 *
 * <p><strong>Read this before trusting the word "exact": these bodies are NOT read from the shipped
 * yml, and this class cannot detect shipped-yml drift at all.</strong> {@code
 * src/test/resources/application.yml} fully shadows {@code src/main/resources/application.yml} and
 * contains no {@code management} block whatsoever, so the {@code @TestPropertySource} below is what
 * supplies the configuration — it replays the shipped values by hand. Measured, not assumed: adding
 * {@code management.endpoint.health.show-details: always} to the SHIPPED yml and running the whole
 * suite gives <em>119/119 green</em>. The same key injected below turns two of these rows red. So a
 * green here means "the endpoint renders this body under these values", never "the shipped file
 * still holds these values" — and the drift it cannot see is precisely the disclosure described in
 * the paragraph above. Closing that half is the shadow slice's job (contract §6.9, §10); it is
 * deliberately NOT worked around here, because asserting against the test yml would make the pin
 * blinder rather than better. This note lives in the test file on purpose: {@code agent_docs/} is
 * gitignored, so the committed artefact is the only durable home for the limit.
 *
 * <p>One asymmetry worth knowing when reading a failure: under a {@code show-details} flip the root
 * and readiness rows go red but liveness does not. Boot's auto-configured probe groups hardcode
 * {@code never}, while {@code readiness} — explicitly configured here with {@code
 * readinessState,db} — inherits the global setting. The liveness row is still killed by a
 * permit-matcher change; it simply cannot be killed by that particular drift.
 *
 * <p>The security configuration itself is production code, untouched.
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
  void health_withoutToken_returns200_withExactRenderedBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"));
  }

  @Test
  void healthReadiness_withoutToken_returns200_withExactRenderedBody() throws Exception {
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
        "readiness body must stay 15 bytes: " + new String(body, StandardCharsets.UTF_8));
  }

  @Test
  void healthLiveness_withoutToken_returns200_withExactRenderedBody() throws Exception {
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
        "liveness body must stay 15 bytes: " + new String(body, StandardCharsets.UTF_8));
  }

  /**
   * Empty today because no info contributor is enabled. Boot 4.1 adds {@code process.*} keys to
   * this endpoint by default, which is exactly the kind of unannounced disclosure the exact body —
   * and not {@code status().isOk()} — is here to surface.
   */
  @Test
  void info_withoutToken_returns200_withExactRenderedBody() throws Exception {
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
        // Content-Type before the body, so a media-type drift is attributed to the media type
        // rather than surfacing as "No value at JSON path" from the matcher that ran first.
        .andExpect(content().contentType("application/json"))
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
  }
}
