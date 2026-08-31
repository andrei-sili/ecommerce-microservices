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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
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
 * flips, {@code components} appears and goes to any unauthenticated caller on the pod network —
 * while {@code $.status} stays {@code "UP"} and stays green. Measured at {@code a9b82af} rather
 * than described in the abstract: the leaked inventory is {@code database: "PostgreSQL"}, {@code
 * validationQuery: "isValid()"}, SSL chain state, disk totals and the server's absolute host
 * filesystem path. See {@link #health_withoutToken_returns200_withExactRenderedBody()} for the
 * probe and its exact failure text.
 *
 * <p><strong>These bodies ARE read from the shipped yml — that half was closed by the S-shadow
 * slice.</strong> This class used to carry a {@code @TestPropertySource} replaying {@code
 * management.endpoints.web.exposure.include}, {@code management.endpoint.health.probes.enabled} and
 * {@code management.endpoint.health.group.readiness.include} by hand, because {@code
 * src/test/resources/application.yml} shadowed the shipped file and contained no {@code management}
 * block at all. A green then meant "the endpoint renders this body under these values", never "the
 * shipped file still holds these values" — measured at the time: adding {@code
 * management.endpoint.health.show-details: always} to the SHIPPED yml left the whole suite green.
 * The test config is now a profile overlay that deliberately declares no {@code management} keys,
 * so the values come from {@code src/main/resources/application.yml} and that same mutation turns
 * the root and readiness rows below red. <strong>Do not reintroduce a {@code @TestPropertySource}
 * for any {@code management.*} key here</strong> — it would re-blind the class to exactly the
 * disclosure described in the paragraph above. This note lives in the test file on purpose: {@code
 * agent_docs/} is gitignored, so the committed artefact is the only durable home for the limit and
 * its closure.
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
   * the exact key set is what makes an added {@code components} key visible here.
   *
   * <p><strong>Asserted key-wise rather than byte-wise, and only on ordering.</strong> This row was
   * {@code content().string(...)} until the Boot 4.1 bump. Boot 4.1 renders actuator bodies through
   * its own {@code EndpointJsonMapper} ({@code JacksonEndpointAutoConfiguration}), a different bean
   * from the application mapper, which no {@code spring.jackson.*} or {@code management.*} property
   * configures — so the two keys come out alphabetically and nothing in configuration can hold them
   * in declaration order. Measured here rather than inferred: at the FREEZE commit, with Boot's
   * Jackson-2-defaults compatibility flag ON and every other body in the suite unmoved, this was
   * one of exactly two failures fleet-shaped like each other, both on this same body: {@code
   * expected:<{"status":"UP",...}> but was:<{"groups":[...],"status":"UP"}>}.
   *
   * <p>Key order is non-binding (contract A4), so the new order is deliberately
   * <strong>not</strong> re-pinned — doing so would re-break on the next Boot patch for no
   * contractual reason. What the byte-exact form actually protected is kept whole: {@link
   * JsonCompareMode#STRICT} fails on an extra key, so a {@code components} inventory appearing
   * beside {@code status} still reddens this row. Do not "tidy" this to the one-argument {@code
   * json(String)} overload — that one is LENIENT and would pass straight through the disclosure
   * this row exists to catch.
   *
   * <p><strong>That last claim is measured, not reasoned.</strong> It is a claim about an assertion
   * this slice deliberately weakened, so it does not get to rest on argument. Probed at {@code
   * a9b82af} by adding {@code show-details: always} to the SHIPPED yml — the exact disclosure this
   * row exists to catch, not an arbitrary edit — and re-running {@code ./mvnw -B -ntp clean verify}
   * at full suite scope: 4 of 138 red, this row among them, failing with {@code Unexpected:
   * components}. The comparator names the extra key. The relaxation therefore cost the ordering pin
   * and nothing else.
   *
   * <p><strong>And a second probe shows it is {@link JsonCompareMode#STRICT} specifically that does
   * the catching</strong> — which is the half the first probe cannot answer. Same {@code
   * show-details: always} on the SHIPPED yml, but with this call additionally relaxed to the
   * one-argument {@code json(String)} overload: the row goes from RED back to
   * <strong>GREEN</strong>. So the second argument is not decoration. Delete it and this row keeps
   * passing through exactly the disclosure above — a silent regression, green in CI, on the fleet's
   * RS256 signer. The warning in the previous paragraph was reasoning until this differential; it
   * is now a measurement, and that is why the mode is named explicitly instead of defaulted.
   *
   * <p>Under that mutation the tokenless body carries {@code database: "PostgreSQL"} and {@code
   * validationQuery: "isValid()"} from the datasource, {@code readinessState}, and — on the wire
   * row in {@code RealServletContainerWireIntegrationTest} — SSL chain state, disk {@code total} /
   * {@code free} / {@code threshold} and the server's <em>absolute path on the host
   * filesystem</em>. All of it unauthenticated, on the pod network, from the service that signs the
   * fleet's RS256 tokens and holds the bcrypt hashes. {@code $.status} stays {@code "UP"}
   * throughout, which is exactly why a subset assertion here would be worthless.
   *
   * <p>One asymmetry, so nobody later "fixes" the wrong row: under that same mutation the liveness
   * row below stays GREEN, and that is correct rather than vacuous. Boot's auto-configured
   * availability-probe group hardcodes {@code show-details: never}, so only the root aggregate and
   * the explicitly-declared {@code readiness} group inherit the endpoint setting (contract §4f).
   */
  @Test
  void health_withoutToken_returns200_withExactRenderedBody() throws Exception {
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
