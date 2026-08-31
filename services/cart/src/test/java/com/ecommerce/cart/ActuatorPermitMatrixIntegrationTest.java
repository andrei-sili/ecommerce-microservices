package com.ecommerce.cart;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.ecommerce.cart.support.JwtTestKeys;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

  /** The error-envelope media type, captured exactly — a charset or problem+json drift fails. */
  private static final String ENVELOPE_JSON = "application/json";

  /**
   * The SHIPPED body: exact key SET and exact values, strict so a GAINED key still fails. {@code
   * management.endpoint.health.show-details} is set nowhere in this repo, so production runs the
   * default {@code never}; cart has no {@code src/test/resources}, so this row reads the real
   * {@code application.yml} and is evidence about production, not about a test fixture. A {@code
   * $.status} subset assertion cannot see a flipped default, which would disclose the DB product
   * and version to any unauthenticated caller on the cluster network (F4) — strict mode can,
   * because an added {@code components} key fails it.
   *
   * <p>The {@code groups} array is part of the shipped disclosure — {@code
   * management.endpoint.health.probes.enabled: true} registers the liveness/readiness groups and
   * the aggregate endpoint lists them even at {@code show-details: never}.
   *
   * <p><b>Why this is a strict JSON comparison and not a byte-string one.</b> It used to assert the
   * raw string. At Spring Boot 4.1.1 the actuator emits the same keys and values in alphabetical
   * order — {@code {"groups":[...],"status":"UP"}} — and the migration's serialization-freeze flag
   * cannot hold it, because the actuator serializes through its own mapper (the endpoint mapper
   * shipped in the actuator module) rather than through the application mapper that {@code
   * spring.jackson.*} configures. Measured rather than assumed: the endpoint emits the reordered
   * body identically with the freeze flag set to true and to false, while in the same run the 401
   * envelope written through the application mapper keeps its declaration order. Key order is
   * deliberately not part of this service's wire contract — JSON object members are unordered, and
   * every consumer reads these bodies by key — so asserting it here was pinning a property the
   * contract says must not be pinned. Strict JSON comparison keeps everything this row exists for
   * and drops only the ordering claim.
   *
   * <p><b>{@link JsonCompareMode#STRICT} is load-bearing and must not be dropped.</b> The one-arg
   * {@code json(String)} overload compares LENIENTLY, which tolerates keys the response GAINED —
   * and a gained key is the disclosure regression this row exists to catch. Proven, not assumed:
   * flipping the shipped {@code show-details} to {@code always} fails this row with {@code
   * Unexpected: components}. So "simplifying" the call by deleting the second argument would leave
   * the row green through exactly the change it guards against.
   */
  @Test
  void health_tokenless_returns200_withTheExactShippedBody() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ACTUATOR_JSON))
        .andExpect(
            content()
                .json(
                    "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                    JsonCompareMode.STRICT));
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
        .andExpect(content().contentType(ENVELOPE_JSON))
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
            // A7: the access-denied handler must keep application/json, never problem+json.
            .andExpect(content().contentType(ENVELOPE_JSON))
            .andExpect(jsonPath("$.error", is("FORBIDDEN")))
            .andExpect(jsonPath("$.message", is("Insufficient permissions")))
            .andExpect(jsonPath("$.path", is("/actuator/env")))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>();
    body.propertyNames().forEach(keys::add);
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "403 envelope must expose exactly the four contract keys");
  }
}
