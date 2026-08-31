package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.JsonShape;
import com.ecommerce.payment.support.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The wire {@code Content-Type} per rendering path, measured through a REAL Tomcat over a real
 * socket — the only place in this suite where that string is observable.
 *
 * <p><b>Payment emits two different strings, and that is the current production truth.</b> The
 * hand-written entry point writes through {@code response.getWriter()} without setting an encoding,
 * so Tomcat appends the container default; the converter stack sets the type itself and appends
 * nothing:
 *
 * <pre>
 *   path A — RestAuthenticationEntryPoint, getWriter  → application/json;charset=ISO-8859-1
 *   path B — converter stack (GlobalExceptionHandler) → application/json
 * </pre>
 *
 * <p><b>Why this class has to exist.</b> MockMvc normalises the encoding away: every MockMvc
 * assertion in this suite sees {@code application/json} on BOTH paths, so a test asserting that the
 * two paths render identically passes there while being false on the wire. That is exactly what
 * {@code PaymentBodyShapeIntegrationTest} used to assert. A green test standing on a false fact is
 * worse than a vacuous one, because it gets cited as evidence — so the identity claim was removed
 * from the MockMvc suite and the two real strings are pinned here instead, each on its own path.
 *
 * <p><b>Do not "restore" an identity assertion when these two constants disagree.</b> They are
 * SUPPOSED to disagree today. Identity becomes the correct claim only once the entry points are
 * given an explicit encoding; when that lands, update {@link #PATH_A_CONTENT_TYPE} deliberately and
 * with a measurement, never by making the two constants equal to make a test go green.
 *
 * <p>Kept out of {@code agent_docs/} on purpose: that tree is gitignored, so the measured baseline
 * would not survive. The committed constants below are the artefact.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentTypeWireIntegrationTest extends AbstractIntegrationTest {

  /** Measured on Tomcat at 3.5.16. The charset is Tomcat's default, not something we choose. */
  private static final String PATH_A_CONTENT_TYPE = "application/json;charset=ISO-8859-1";

  /** Measured on Tomcat at 3.5.16. The converter sets the type and adds no charset parameter. */
  private static final String PATH_B_CONTENT_TYPE = "application/json";

  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  @LocalServerPort private int port;

  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  @Test
  void pathA_entryPoint401_carriesTheContainerAppendedCharset() throws Exception {
    HttpResponse<String> response = get("/api/v1/payments/" + UUID.randomUUID(), null);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(contentType(response))
        .as("getWriter() sets no encoding, so Tomcat appends its default")
        .isEqualTo(PATH_A_CONTENT_TYPE);
    assertThat(JsonShape.keysOf(response.body()))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("UNAUTHORIZED");
  }

  @Test
  void pathB_frameworkError404_carriesNoCharsetParameter() throws Exception {
    HttpResponse<String> response = get("/api/v1/payments-typo", USER);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(contentType(response))
        .as("the converter stack sets the type itself and appends no charset")
        .isEqualTo(PATH_B_CONTENT_TYPE);
    assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("RESOURCE_NOT_FOUND");
  }

  /**
   * A14's webhook 401 is payment's second, deliberately different envelope — and on the wire it
   * renders like the converter stack, NOT like the other 401. So payment's two 401 responses carry
   * two different {@code Content-Type} strings, which no MockMvc assertion can see.
   */
  @Test
  void pathB_webhook401_rendersLikeTheConverterStack_notLikeTheOther401() throws Exception {
    HttpResponse<String> response = postJson("/api/v1/payments/webhook", "{}");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(contentType(response)).isEqualTo(PATH_B_CONTENT_TYPE);
    assertThat(contentType(response))
        .as("the two 401s differ on the wire; asserting them identical is the M-3 regression")
        .isNotEqualTo(PATH_A_CONTENT_TYPE);
    assertThat(JsonShape.keysOf(response.body()))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    assertThat(JsonPath.<String>read(response.body(), "$.error"))
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
  }

  /**
   * A8's other path-A renderer. {@code RestAccessDeniedHandler} writes through {@code
   * response.getWriter()} exactly as the entry point does, so it must carry the same
   * container-appended charset — but "identical code shape" is an argument, not a measurement, and
   * A8 is a row about measured container strings. So the 403 is measured on the wire rather than
   * inferred from the 401 above.
   *
   * <p>403 is reachable here because {@code SecurityConfig} terminates in {@code
   * .anyRequest().denyAll()}: any authenticated request to a path outside {@code /api/v1/**} and
   * the actuator permits lands on the access-denied handler. {@code /internal-denied} is never
   * dispatcher-mapped, so its status comes from the security chain rather than from a controller —
   * which is what makes this a test of the chain and not of a route.
   */
  @Test
  void pathA_accessDenied403_carriesTheSameContainerAppendedCharset() throws Exception {
    HttpResponse<String> response = get("/internal-denied", USER);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(contentType(response))
        .as("the access-denied handler is the other getWriter() path, so it renders like path A")
        .isEqualTo(PATH_A_CONTENT_TYPE);
    assertThat(JsonShape.keysOf(response.body()))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("FORBIDDEN");
    assertThat(JsonPath.<String>read(response.body(), "$.message"))
        .isEqualTo("Insufficient permissions");
    assertThat(JsonPath.<String>read(response.body(), "$.path")).isEqualTo("/internal-denied");
  }

  /**
   * A8's second clause: the {@code Content-Type} strings differ per path, but the KEY SET does not.
   * Both halves matter and they pull in opposite directions — asserting the types identical was the
   * false claim this class was built to retire, while letting the shapes diverge would mean a
   * client has to know which internal renderer produced an error before it can parse it.
   *
   * <p>Asserted as a set comparison across all three paths in one place rather than three separate
   * four-key assertions, because what is being pinned is the EQUALITY of the shapes, not each shape
   * on its own — those are already pinned in the rows above.
   */
  @Test
  void allThreeRenderingPaths_emitTheSameFourKeySet_despiteDifferentContentTypes()
      throws Exception {
    Set<String> entryPoint401 =
        JsonShape.keysOf(get("/api/v1/payments/" + UUID.randomUUID(), null).body());
    Set<String> accessDenied403 = JsonShape.keysOf(get("/internal-denied", USER).body());
    Set<String> converterStack404 = JsonShape.keysOf(get("/api/v1/payments-typo", USER).body());

    assertThat(entryPoint401)
        .as("the contract envelope is the same four keys whichever renderer produced it")
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path")
        .isEqualTo(accessDenied403)
        .isEqualTo(converterStack404);
  }

  private static String contentType(HttpResponse<String> response) {
    return response.headers().firstValue("content-type").orElse("<absent>");
  }

  private HttpResponse<String> get(String path, String authorization) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return send(request.build());
  }

  private HttpResponse<String> postJson(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private static HttpResponse<String> send(HttpRequest request) throws Exception {
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
