package com.ecommerce.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.support.AbstractBrokerIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The permit side of the security chain: the probe endpoints must answer <b>200 without a
 * token</b>. Nothing pinned this before — every actuator assertion in the suite was about the deny
 * side — so a matcher change that turned the probes into 401s would have shipped green and taken
 * readiness (and therefore rollouts) down with it. Security 7 rewrites every string matcher, which
 * makes this the cheapest possible guard on the most expensive possible failure.
 *
 * <p>Runs against BOTH real dependencies (Postgres and RabbitMQ via Testcontainers) rather than the
 * mocked-relay base: {@code /actuator/health} aggregates the {@code rabbit} indicator too, so
 * without a broker the endpoint answers 503 for reasons that have nothing to do with the permit
 * being pinned. The production exposure list and probe settings are mirrored here because the
 * shared test yml keeps actuator defaults — the same technique {@code
 * MetricsEndpointIntegrationTest} uses.
 */
@TestPropertySource(
    properties = {
      "management.endpoints.web.exposure.include=health,info,prometheus",
      "management.endpoint.health.probes.enabled=true",
      "management.endpoint.health.group.readiness.include=readinessState,db"
    })
class ActuatorPermitMatrixIntegrationTest extends AbstractBrokerIntegrationTest {

  /**
   * Actuator's own vendor type, captured on 3.5.16. Asserted exactly (A9): {@code
   * contentTypeCompatibleWith} treats a charset change as equal and cannot catch the drift.
   */
  private static final MediaType ACTUATOR_JSON =
      MediaType.parseMediaType("application/vnd.spring-boot.actuator.v3+json");

  @Autowired private MockMvc mockMvc;

  /**
   * The EXACT body, not {@code $.status}. A subset assertion passes just as happily on a body that
   * has GAINED {@code components}/{@code details} — which is what {@code show-details} drifting to
   * {@code always} produces, and it discloses DB hostnames and connection state to anything on the
   * pod network (F4). {@code groups} is present here because probes are enabled in production, so
   * it is part of the pin, not noise.
   */
  @Test
  void health_tokenless_returns200_exactBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"));
  }

  @Test
  void healthReadiness_tokenless_returns200_exactBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\"}"));
  }

  @Test
  void healthLiveness_tokenless_returns200_exactBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\"}"));
  }

  /**
   * Empty body is part of the pin: {@code /actuator/info} is readable unauthenticated by anything
   * on the internal network, so a framework upgrade that starts contributing process or truststore
   * details must surface as a red test, not as a quiet disclosure.
   */
  @Test
  void info_tokenless_returns200_emptyBody() throws Exception {
    mockMvc
        .perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{}"));
  }

  /** Control: the permits are scoped, not a blanket "everything is open" on this context. */
  @Test
  void businessPath_tokenless_still401() throws Exception {
    mockMvc
        .perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
  }
}
