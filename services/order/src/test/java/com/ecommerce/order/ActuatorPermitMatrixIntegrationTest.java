package com.ecommerce.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.RabbitTestBroker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Permit side of the security matrix, tokenless. Every other suite proves only the deny side (401
 * on a protected route), so a matcher that silently stopped matching would leave the suite green
 * while the compose healthcheck (which greps {@code "status":"UP"}) and the gateway's active probe
 * start failing — nothing with {@code depends_on: service_healthy} would ever start.
 *
 * <p>Runs against the REAL dependencies (Postgres + RabbitMQ containers) so {@code
 * /actuator/health} aggregates to UP the way it does in compose. Stubbing the broker health
 * indicator out would make the 200 an artefact of the test config rather than of the shipped one.
 */
class ActuatorPermitMatrixIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @BeforeAll
  static void startBroker() {
    RabbitTestBroker.start();
  }

  @DynamicPropertySource
  static void brokerProperties(DynamicPropertyRegistry registry) {
    RabbitTestBroker.registerConnection(registry);
  }

  /**
   * Pins the body AS SHIPPED: with {@code probes.enabled} the aggregate carries the group index
   * alongside the status, so byte-equality with {@code {"status":"UP"}} is NOT the 3.5.16
   * behaviour. Strict matching keeps the pin able to fail on a body that gains component details.
   */
  @Test
  void bareHealth_tokenless_returns200_statusUpAndGroupIndexOnly() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(
            content().json("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}", true));
  }

  @Test
  void readinessProbe_tokenless_returns200_statusUpAndNothingElse() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", true));
  }

  @Test
  void livenessProbe_tokenless_returns200_statusUpAndNothingElse() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", true));
  }

  @Test
  void info_tokenless_returns200_emptyObject() throws Exception {
    mockMvc
        .perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(content().json("{}", true));
  }
}
