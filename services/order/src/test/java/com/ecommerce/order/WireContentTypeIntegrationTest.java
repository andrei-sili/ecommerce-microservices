package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The A9 pin on the REAL wire. Every other Content-Type assertion in this service runs through
 * MockMvc, which reports the value the framework computed BEFORE the container touched it — so it
 * cannot see what a client actually receives. This is the only class that can.
 *
 * <p><b>The two paths do not agree, and that is the finding.</b> Measured on this service's own
 * Tomcat (see {@link #PATH_A_CONTENT_TYPE} / {@link #PATH_B_CONTENT_TYPE}):
 *
 * <ul>
 *   <li><b>Path A</b> — 401/403 written by hand in {@code RestAuthenticationEntryPoint} / {@code
 *       RestAccessDeniedHandler} via {@code response.getWriter()}. The servlet spec makes the
 *       container stamp its default charset onto a writer response, so the wire carries {@code
 *       ;charset=ISO-8859-1}. MockMvc normalises this away and reports a bare {@code
 *       application/json}.
 *   <li><b>Path B</b> — everything rendered by the converter stack (controllers and {@code
 *       GlobalExceptionHandler}). No charset parameter.
 * </ul>
 *
 * <p><b>Do NOT restore an identity assertion here.</b> The natural instinct on seeing two different
 * strings is to assert they are equal, or to "fix" path A to match path B. Both are wrong: the
 * difference is real 3.5.16 behaviour and this class exists to pin it, so that the Boot 4.1 /
 * Tomcat 11 / Servlet 6.1 bump has something to be compared against. If these strings change at
 * 4.1.0, that is a finding to report — not a fixture to update.
 *
 * <p>Values are constants in this committed file on purpose: {@code agent_docs/} is gitignored, so
 * the test IS the artefact that carries the measurement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WireContentTypeIntegrationTest extends AbstractIntegrationTest {

  /**
   * Measured 2026-08-25 on Spring Boot 3.5.16 / embedded Tomcat, read with {@code
   * java.net.http.HttpClient} so nothing normalises the header: {@code GET /api/v1/orders} with no
   * Authorization → {@code application/json;charset=ISO-8859-1}.
   */
  private static final String PATH_A_CONTENT_TYPE = "application/json;charset=ISO-8859-1";

  /**
   * Measured in the same run: {@code GET /api/v1/orders} with a valid token → {@code
   * application/json}, and the {@code GlobalExceptionHandler} 404 → {@code application/json}. The
   * converter stack emits no charset parameter.
   */
  private static final String PATH_B_CONTENT_TYPE = "application/json";

  @LocalServerPort private int port;

  private HttpResponse<String> get(String path, String authorization) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    // A default client, deliberately: it prefers HTTP/2 and would attempt an h2c upgrade on
    // cleartext. Pinning HTTP/1.1 here would hide which transport the container actually serves.
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String contentType(HttpResponse<String> response) {
    return response.headers().firstValue("Content-Type").orElse("<absent>");
  }

  /**
   * Path A: the hand-written entry point. This is the row MockMvc cannot discharge — it reports
   * {@code application/json} for this exact response.
   */
  @Test
  void unauthenticated401_wireContentType_carriesTheContainerCharset() throws Exception {
    HttpResponse<String> response = get("/api/v1/orders", null);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(contentType(response))
        .as("path A is written via response.getWriter(); the container stamps its default charset")
        .isEqualTo(PATH_A_CONTENT_TYPE);
    assertThat(response.body()).contains("\"error\":\"UNAUTHORIZED\"");
  }

  /** Path B, success side: the converter stack emits no charset parameter. */
  @Test
  void authenticated200_wireContentType_hasNoCharsetParameter() throws Exception {
    HttpResponse<String> response =
        get("/api/v1/orders", TestJwt.bearer(TestJwt.token("7", List.of("USER"))));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(contentType(response)).isEqualTo(PATH_B_CONTENT_TYPE);
  }

  /** Path B, error side: the advice-rendered envelope agrees with the success side, not with A. */
  @Test
  void adviceRendered404_wireContentType_matchesPathBNotPathA() throws Exception {
    HttpResponse<String> response =
        get(
            "/api/v1/orders/" + UUID.randomUUID(),
            TestJwt.bearer(TestJwt.token("7", List.of("USER"))));

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(contentType(response)).isEqualTo(PATH_B_CONTENT_TYPE);
    assertThat(contentType(response))
        .as("the two paths genuinely differ at 3.5.16 — see the class javadoc before 'fixing' this")
        .isNotEqualTo(PATH_A_CONTENT_TYPE);
  }

  /**
   * Recorded because order is the one service carrying an HTTP/1.1 pin on its outbound JDK client
   * (h2c trap, {@code rules/testing.md}). That pin is on a different transport path from this
   * inbound one, so the two should not interact — asserted rather than assumed. A default client
   * preferring HTTP/2 lands on HTTP/1.1 here because the embedded Tomcat does not advertise h2c.
   */
  @Test
  void inboundTransport_negotiatesHttp11_notH2c() throws Exception {
    HttpResponse<String> response = get("/api/v1/orders", null);

    assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
  }
}
