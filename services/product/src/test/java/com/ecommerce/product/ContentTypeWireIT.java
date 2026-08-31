package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.product.support.JsonShape;
import com.ecommerce.product.support.JwtTestKeys;
import com.ecommerce.product.support.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The A9 invariant on the WIRE: the exact {@code Content-Type} bytes a real Tomcat puts on the
 * socket, read with {@link HttpClient} so nothing Spring-side normalises them first.
 *
 * <p><b>Why MockMvc cannot discharge this row.</b> Every other Content-Type assertion in this suite
 * runs through MockMvc, which reports the NORMALISED value: it shows {@code application/json} for
 * the hand-written entry point even though the container actually emits {@code
 * application/json;charset=ISO-8859-1}. Those assertions are still load-bearing — they catch a
 * charset drift, proven 20/25 RED against 30/30 GREEN under the same mutation — but they pin the
 * normalised string, not the wire string. Only a real servlet container shows the difference.
 *
 * <p><b>The two paths are SUPPOSED to disagree today.</b> Product writes error envelopes two ways,
 * and they produce different bytes:
 *
 * <ul>
 *   <li><b>Path A</b> — {@code RestAuthenticationEntryPoint}, {@code RestAccessDeniedHandler} and
 *       {@code InternalApiKeyFilter} call {@code response.setContentType("application/json")} and
 *       write through {@code response.getWriter()}. The container appends its own default charset.
 *       Product is the only service with a third path-A writer, the internal-API-key filter.
 *   <li><b>Path B</b> — everything rendered by the converter stack (200 bodies, domain errors and
 *       framework errors via {@code GlobalExceptionHandler}). Jackson's converter emits no charset
 *       parameter.
 * </ul>
 *
 * <p><b>DO NOT "fix" this by asserting the two paths are identical.</b> An identity assertion is
 * wrong today and would go green only by accident. It becomes correct after the path-A writers set
 * an explicit encoding — which is a production change, deliberately out of scope here. Until then
 * the disagreement IS the pinned behaviour, and a migration that silently aligns them must be
 * visible.
 *
 * <p>Values below were measured on Boot 3.5.16 against this service's own embedded Tomcat, via
 * {@code HttpResponse.headers().allValues("Content-Type")}. Per contract §4e each service pins what
 * IT measured; these strings are not portable to another service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentTypeWireIT {

  /**
   * Path A, measured: {@code response.getWriter()} with no explicit encoding, so Tomcat appends its
   * default. NOT UTF-8 — the envelope is ASCII today, so nothing has forced the question.
   */
  private static final String WIRE_PATH_A = "application/json;charset=ISO-8859-1";

  /** Path B, measured: the Jackson converter emits no charset parameter. */
  private static final String WIRE_PATH_B = "application/json";

  /** Actuator's versioned media type, measured on the same run. */
  private static final String WIRE_ACTUATOR = "application/vnd.spring-boot.actuator.v3+json";

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("product_db")
          .withUsername("product")
          .withPassword("product");

  @BeforeAll
  static void startContainer() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("security.jwt.secret", () -> TestJwt.SECRET);
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("security.internal-api-key", () -> "test-internal-api-key");
    registry.add("reservation.sweeper.delay-ms", () -> String.valueOf(Long.MAX_VALUE / 2));
  }

  @LocalServerPort int port;

  @Autowired private ObjectMapper objectMapper;

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .version(HttpClient.Version.HTTP_1_1)
          .build();

  @Test
  void pathA_authenticationEntryPoint_emitsContainerCharsetOnTheWire() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri("/api/v1/products"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

    assertThat(response.statusCode()).isEqualTo(401);
    assertContentType(response, WIRE_PATH_A);
    assertThat(response.body()).contains("\"error\":\"UNAUTHORIZED\"");
  }

  @Test
  void pathA_accessDeniedHandler_emitsContainerCharsetOnTheWire() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri("/api/v1/products"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestJwt.token("2", List.of("USER")))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

    assertThat(response.statusCode()).isEqualTo(403);
    assertContentType(response, WIRE_PATH_A);
    assertThat(response.body()).contains("\"error\":\"FORBIDDEN\"");
  }

  // product's third path-A writer, which no other service has: the filter answers 401 directly,
  // bypassing the entry point entirely, so it can drift independently of the other two.
  @Test
  void pathA_internalApiKeyFilter_emitsContainerCharsetOnTheWire() throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri("/api/v1/inventory/reservations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

    assertThat(response.statusCode()).isEqualTo(401);
    assertContentType(response, WIRE_PATH_A);
    assertThat(response.body()).contains("\"message\":\"Invalid internal API key\"");
  }

  @Test
  void pathB_successBody_emitsNoCharsetOnTheWire() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(uri("/api/v1/products?page=0&size=5")).GET());

    assertThat(response.statusCode()).isEqualTo(200);
    assertContentType(response, WIRE_PATH_B);
    assertThat(response.body()).contains("\"total_elements\"");
  }

  @Test
  void pathB_domainError_emitsNoCharsetOnTheWire() throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(uri("/api/v1/products/999999")).GET());

    assertThat(response.statusCode()).isEqualTo(404);
    assertContentType(response, WIRE_PATH_B);
    assertThat(response.body()).contains("\"error\":\"PRODUCT_NOT_FOUND\"");
  }

  @Test
  void pathB_frameworkError_emitsNoCharsetOnTheWire() throws Exception {
    HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/nope")).GET());

    assertThat(response.statusCode()).isEqualTo(404);
    assertContentType(response, WIRE_PATH_B);
    assertThat(response.body()).contains("\"error\":\"RESOURCE_NOT_FOUND\"");
  }

  /**
   * The wire body is pinned key-wise rather than byte-wise, and only on ordering.
   *
   * <p>Boot 4.1 renders actuator bodies through its own {@code EndpointJsonMapper} ({@code
   * JacksonEndpointAutoConfiguration}), a different bean from the application mapper that {@code
   * spring.jackson.*} does not configure. Measured on 4.1.1 with Boot's Jackson-2-defaults
   * compatibility flag unset, false and true: the endpoint mapper emits keys alphabetically in all
   * three cases while the application mapper follows the flag, so nothing in configuration can hold
   * these bytes in declaration order. (The flag is deliberately not spelled here — the wave's
   * escape-hatch scan greps for that key, and prose explaining a finding must not read as a
   * surviving escape hatch.) Key order is non-binding (contract A4) and neither real consumer reads
   * it — the compose healthcheck greps the substring {@code "status":"UP"}, Kong's probe reads only
   * the status code.
   *
   * <p>Everything else the byte-exact form held is kept: no whitespace, an exact two-key set (so a
   * {@code components} inventory still fails here), and both values verbatim.
   */
  @Test
  void actuatorHealth_emitsTheVersionedActuatorMediaTypeOnTheWire() throws Exception {
    HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/actuator/health")).GET());

    assertThat(response.statusCode()).isEqualTo(200);
    assertContentType(response, WIRE_ACTUATOR);

    String body = response.body();
    assertThat(body).doesNotContain(" ").doesNotContain("\n");
    JsonNode health = objectMapper.readTree(body);
    JsonShape.assertKeysExactly(health, "status", "groups");
    assertThat(health.get("status").textValue()).isEqualTo("UP");
    assertThat(health.get("groups").toString()).isEqualTo("[\"liveness\",\"readiness\"]");
  }

  /**
   * The header must appear EXACTLY once with EXACTLY this value. Asserting the whole list rather
   * than {@code firstValue()} also catches a duplicated header, which a proxy or a second writer
   * would produce and which a single-value read cannot see.
   */
  private void assertContentType(HttpResponse<String> response, String expected) {
    assertThat(response.headers().allValues("Content-Type"))
        .as("wire Content-Type for %s", response.uri())
        .containsExactly(expected);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
