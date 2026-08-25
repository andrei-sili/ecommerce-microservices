package com.ecommerce.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The permit side of the security chain, and the exact bytes the probe endpoints disclose.
 *
 * <p>Two independent regressions are pinned here. Losing a permit matcher turns the K8s probes into
 * 401s against a chain that terminates in {@code denyAll()} — a runtime outage, not a compile
 * error. Turning health details on turns these bodies into a component inventory (driver class,
 * validation query, disk paths) served unauthenticated to anything on the pod network.
 *
 * <p>Bodies are asserted <b>byte-for-byte</b>, never by {@code $.status} alone: a subset assertion
 * passes unchanged when {@code components} appears next to {@code status}, which is precisely the
 * disclosure this test exists to catch. product has no {@code src/test/resources}, so these bytes
 * are evidence about the SHIPPED {@code application.yml}, not about a test fixture.
 */
class ActuatorPermitMatrixIT extends AbstractIntegrationTest {

  /** Actuator's versioned media type — a plain {@code application/json} here is already a drift. */
  private static final MediaType ACTUATOR_JSON =
      MediaType.valueOf("application/vnd.spring-boot.actuator.v3+json");

  /** Shipped body of a healthy probe group: status only, no components, no details. */
  private static final String STATUS_UP_ONLY = "{\"status\":\"UP\"}";

  @Autowired private MockMvc mockMvc;

  @Test
  void health_isPermittedWithoutToken_andDisclosesOnlyStatusAndGroups() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"));
  }

  @Test
  void healthReadiness_isPermittedWithoutToken_andDisclosesOnlyStatus() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string(STATUS_UP_ONLY));
  }

  // Liveness is the probe Kubernetes RESTARTS the pod on. It is asserted separately from readiness
  // because the two resolve through different health groups and can regress independently.
  // Note (contract §4f): the AUTO-CONFIGURED liveness group forces show-details NEVER, so a global
  // `show-details: always` leaves this body untouched — but a group DECLARED in the yml inherits
  // the
  // parent setting and does disclose components. This row is falsifiable through that second path;
  // §4f does not mean liveness can never move.
  @Test
  void healthLiveness_isPermittedWithoutToken_andDisclosesOnlyStatus() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string(STATUS_UP_ONLY));
  }

  // Boot 4.1 adds process/truststore entries to /actuator/info. This endpoint is unauthenticated on
  // the pod network, so the empty body is pinned exactly rather than asserted to be "some JSON".
  @Test
  void info_isPermittedWithoutToken_andIsEmpty() throws Exception {
    mockMvc
        .perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{}"));
  }
}
