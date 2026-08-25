package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins what only a real servlet container can show: the {@code Content-Type} bytes that actually go
 * out on the wire, and the actuator bodies as a real HTTP client receives them.
 *
 * <p><strong>Why MockMvc cannot discharge this.</strong> Every other assertion in this suite runs
 * through {@code MockMvc}, which reports the <em>normalised</em> content type — the value Spring
 * computed, not the header Tomcat wrote. The two genuinely differ across the fleet: on the four
 * services whose entry point writes via {@code response.getWriter()}, the container appends a
 * charset and the wire string is {@code application/json;charset=ISO-8859-1} while MockMvc still
 * says {@code application/json}. So this class drives real Tomcat on a random port and reads the
 * raw header with {@link HttpClient}, deliberately bypassing anything Spring-side that would
 * normalise it on the way back.
 *
 * <p><strong>user is the fleet outlier, and this is the measurement.</strong> {@code
 * RestAuthenticationEntryPoint} is the only one of the five that writes via {@code
 * response.getOutputStream()} rather than {@code getWriter()}. No writer is obtained, so the
 * servlet contract never appends a charset, and path A comes back bare. Captured at 2e7cccd against
 * real Tomcat, {@code ./mvnw -B -ntp test -Dtest=RealServletContainerWireIntegrationTest}:
 *
 * <pre>
 *   path A  GET /api/v1/users/me  no token   -> 401  Content-Type: application/json
 *   path A  GET /api/v1/users/me  bad token  -> 401  Content-Type: application/json
 *   path B  GET /api/v1/users     token      -> 404  Content-Type: application/json
 *   path B  GET /api/v1/users/me  token      -> 200  Content-Type: application/json
 * </pre>
 *
 * <p><strong>Do NOT collapse the two constants below into one, and do NOT add an assertion that
 * they are equal.</strong> They hold the same string today, but that equality is incidental — it
 * falls out of {@code getOutputStream()} declining to append a charset, not out of the two paths
 * being aligned by anyone's intent. They are configured independently and can drift independently:
 * switching that single call to {@code getWriter()} moves path A to {@code
 * application/json;charset=ISO-8859-1} and leaves path B untouched, which is precisely the drift
 * this class exists to catch. An identity assertion would pass today, encode the coincidence as
 * contract, and then invite someone to "fix" a correct one-sided red by making the two constants
 * equal again. If either measurement ever changes, update that constant from a fresh capture —
 * never by copying the other one, and never by copying cart's or payment's string, which are
 * different values for a different reason.
 *
 * <p>This note lives in the committed test because {@code agent_docs/} is gitignored, so the test
 * file is the only durable home for the measurement and its caveat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "management.endpoints.web.exposure.include=health,info,prometheus",
      "management.endpoint.health.probes.enabled=true",
      "management.endpoint.health.group.readiness.include=readinessState,db"
    })
class RealServletContainerWireIntegrationTest extends AbstractIntegrationTest {

  /** Measured: the hand-written entry point, which writes to {@code getOutputStream()}. */
  private static final String PATH_A_WIRE_CONTENT_TYPE = "application/json";

  /** Measured: the message-converter stack. Same string as path A, for an unrelated reason. */
  private static final String PATH_B_WIRE_CONTENT_TYPE = "application/json";

  private static final String ACTUATOR_WIRE_CONTENT_TYPE =
      "application/vnd.spring-boot.actuator.v3+json";

  private static final String PASSWORD = "Sup3rSecret12";

  /** Registrations are not rolled back between rows here, so each row takes a fresh identity. */
  private static final AtomicInteger SEQ = new AtomicInteger();

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void pathA_401_withoutToken_wireContentTypeCarriesNoCharset() throws Exception {
    HttpResponse<String> response = get("/api/v1/users/me", null);

    assertEquals(401, response.statusCode());
    assertWireContentType(PATH_A_WIRE_CONTENT_TYPE, response, "path A (entry point, 401 no token)");
    assertTrue(
        response.body().contains("\"error\":\"UNAUTHORIZED\""),
        "the row must fail on the content type, not on an empty body: " + response.body());
  }

  @Test
  void pathA_401_withMalformedToken_wireContentTypeCarriesNoCharset() throws Exception {
    HttpResponse<String> response = get("/api/v1/users/me", "not.a.jwt");

    assertEquals(401, response.statusCode());
    assertWireContentType(
        PATH_A_WIRE_CONTENT_TYPE, response, "path A (entry point, 401 bad token)");
  }

  @Test
  void pathB_404_wireContentTypeCarriesNoCharset() throws Exception {
    HttpResponse<String> response = get("/api/v1/users", registerAndLogin());

    assertEquals(404, response.statusCode());
    assertWireContentType(PATH_B_WIRE_CONTENT_TYPE, response, "path B (converter stack, 404)");
    assertTrue(
        response.body().contains("\"error\":\"RESOURCE_NOT_FOUND\""),
        "the row must fail on the content type, not on an empty body: " + response.body());
  }

  @Test
  void pathB_200_wireContentTypeCarriesNoCharset() throws Exception {
    HttpResponse<String> response = get("/api/v1/users/me", registerAndLogin());

    assertEquals(200, response.statusCode());
    assertWireContentType(PATH_B_WIRE_CONTENT_TYPE, response, "path B (converter stack, 200)");
  }

  /**
   * Confirms on a real container what the MockMvc actuator pins assert. Those pins drove a
   * correction to the contract's F1 row (the root aggregate carries {@code groups} and is 49 bytes,
   * not the 15-byte body F1 originally claimed), and that correction rested on MockMvc alone until
   * this row. Same shipped management values, real Tomcat, raw client: same bytes.
   */
  @Test
  void shippedActuatorBodies_onRealTomcat_matchTheMockMvcPins() throws Exception {
    HttpResponse<String> root = get("/actuator/health", null);
    assertEquals(200, root.statusCode());
    assertWireContentType(ACTUATOR_WIRE_CONTENT_TYPE, root, "actuator root");
    assertEquals("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}", root.body());

    assertEquals("{\"status\":\"UP\"}", get("/actuator/health/readiness", null).body());
    assertEquals("{\"status\":\"UP\"}", get("/actuator/health/liveness", null).body());
    assertEquals("{}", get("/actuator/info", null).body());
  }

  private static void assertWireContentType(
      String expected, HttpResponse<String> response, String what) {
    String actual = response.headers().firstValue("Content-Type").orElse(null);
    assertFalse(
        actual != null && actual.contains("problem"),
        what + " must not use application/problem+json, was: " + actual);
    assertEquals(expected, actual, what + " wire Content-Type");
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private String registerAndLogin() throws Exception {
    String email = "wire-" + SEQ.incrementAndGet() + "@example.com";
    HttpResponse<String> registered =
        post(
            "/api/v1/auth/register",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Wire\"}");
    assertEquals(201, registered.statusCode(), "register must succeed: " + registered.body());

    HttpResponse<String> login =
        post(
            "/api/v1/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, login.statusCode(), "login must succeed: " + login.body());
    return login.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
