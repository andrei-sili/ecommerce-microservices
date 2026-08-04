package com.ecommerce.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The permit side of the security chain: the probe endpoints answer 200 with NO Authorization
 * header. Every other actuator assertion in the suite targets {@code /actuator/prometheus}, so the
 * matchers that keep health/info public are otherwise untested — and a chain that terminates in
 * {@code denyAll()} turns a lost matcher into a 401 on the K8s probes, not a compile error.
 */
class ActuatorPermitMatrixIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void health_isPermittedWithoutToken() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void healthReadiness_isPermittedWithoutToken() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void info_isPermittedWithoutToken() throws Exception {
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
  }
}
