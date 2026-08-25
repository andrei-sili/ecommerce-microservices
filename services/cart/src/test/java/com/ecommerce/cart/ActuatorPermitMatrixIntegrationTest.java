package com.ecommerce.cart;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.ecommerce.cart.support.JwtTestKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The PERMIT side of the security chain, which no test covered before: probes and info must answer
 * 200 with NO {@code Authorization} header. Kubernetes and the compose healthchecks call them
 * unauthenticated, so a matcher rewrite that turns any of these into a 401 takes the pods down
 * without failing a single business test — the deny side is heavily pinned, the permit side was
 * not.
 */
class ActuatorPermitMatrixIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  private static final String ACTUATOR_JSON = "application/vnd.spring-boot.actuator.v3+json";

  /**
   * The SHIPPED body, byte-for-byte. {@code management.endpoint.health.show-details} is set nowhere
   * in this repo, so production runs the default {@code never}; cart has no {@code
   * src/test/resources}, so this row reads the real {@code application.yml} and is evidence about
   * production, not about a test fixture. A {@code $.status} subset assertion cannot see a flipped
   * default, which would disclose the DB product and version to any unauthenticated caller on
   * {@code ecommerce-net} or in namespace {@code ecommerce} (F4).
   *
   * <p>The {@code groups} array is part of the shipped disclosure — {@code
   * management.endpoint.health.probes.enabled: true} registers the liveness/readiness groups and
   * the aggregate endpoint lists them even at {@code show-details: never}.
   */
  @Test
  void health_tokenless_returns200_withTheExactShippedBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}"));
  }

  /** Byte-equal {@code {"status":"UP"}} — no {@code components}, no {@code details} (F4). */
  @Test
  void healthReadiness_tokenless_returns200_withTheExactShippedBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\"}"));
  }

  /** Byte-equal {@code {"status":"UP"}} — no {@code components}, no {@code details} (F4). */
  @Test
  void healthLiveness_tokenless_returns200_withTheExactShippedBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{\"status\":\"UP\"}"));
  }

  /**
   * The exact actuator media type and the empty body are both pinned: Boot 4.1 adds {@code
   * process.*} and truststore-certificate contributors to {@code info}, and this endpoint is
   * readable unauthenticated by anything on the cluster network, so a body that stops being {@code
   * &#123;&#125;} is a disclosure change that must be seen, not absorbed.
   */
  @Test
  void info_tokenless_returns200_emptyBody_actuatorMediaType() throws Exception {
    mockMvc
        .perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(content().string("{}"));
  }

  /**
   * Anti-vacuous control: the permits above must be exactly scoped. Without this row a chain that
   * accidentally permitted everything would make all four assertions pass and look like a contract.
   */
  @Test
  void businessRoute_tokenless_stillReturns401_provingThePermitsAreScoped() throws Exception {
    mockMvc
        .perform(get("/api/v1/cart"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
  }

  /**
   * The chain's {@code denyAll()} terminal, reached with a VALID token on an unpermitted actuator
   * path. It renders through {@code RestAccessDeniedHandler}, which bypasses the exception advice
   * entirely — so the 403 envelope had no coverage at all and would drift silently.
   */
  @Test
  void unpermittedActuatorPath_withValidToken_returns403_withExactlyTheFourEnvelopeKeys()
      throws Exception {
    String token = JwtTestKeys.mintRs256(7L, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);

    MvcResult result =
        mockMvc
            .perform(get("/actuator/env").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error", is("FORBIDDEN")))
            .andExpect(jsonPath("$.message", is("Insufficient permissions")))
            .andExpect(jsonPath("$.path", is("/actuator/env")))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>();
    body.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "403 envelope must expose exactly the four contract keys");
  }
}
