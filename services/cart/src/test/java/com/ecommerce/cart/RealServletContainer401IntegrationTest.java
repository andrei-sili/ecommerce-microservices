package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.cart.support.JwtTestKeys;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The A9 media-type pin as PRODUCTION emits it, over a real servlet container.
 *
 * <p>MockMvc and Tomcat disagree here, and the difference is the whole point of the invariant.
 * {@code RestAuthenticationEntryPoint} sets {@code application/json} and then writes through {@code
 * response.getWriter()}; a real container appends the response character encoding to the header,
 * while {@code MockHttpServletResponse} does not. Measured on 3.5.16 against the repackaged jar in
 * {@code eclipse-temurin:21-jre}:
 *
 * <pre>
 * HTTP/1.1 401
 * Content-Type: application/json;charset=ISO-8859-1
 * </pre>
 *
 * <p>The two observers therefore pin DIFFERENT strings, and only this row records the one a real
 * client reads. That matters for the 4.1 comparison: Boot 4.1 ships Tomcat 11 / Servlet 6.1, and
 * the container's default response charset is exactly the kind of thing that moves — a move the
 * MockMvc constant, being a different string, cannot describe either way.
 *
 * <p>Scope of what was demonstrated, so nobody over-reads this: both charset mutations tried on
 * 3.5.16 (an explicit {@code setCharacterEncoding("UTF-8")} in the entry point, and {@code
 * server.servlet.encoding.force-response} in the shipped yml) turn this row AND the MockMvc rows
 * red — {@code CharacterEncodingFilter} applies in the mock servlet environment too. No mutation
 * was found that moves the container string while leaving the MockMvc string fixed, so this row is
 * NOT proven to catch anything the MockMvc rows miss. It is proven to pin the production byte
 * string, which nothing else in the fleet does. Recorded as refuted in {@code api_contracts.md}
 * §4e; the earlier "every in-suite assertion stays green" reading is wrong.
 *
 * <p><b>Scope widened at the Boot 4.1.1 migration, beyond what the class name says.</b> It now pins
 * BOTH rendering paths rather than only the 401: path A is the pair of hand-written renderers
 * writing through {@code getWriter()}, path B is everything the converter stack renders. The two
 * carry different byte strings, and only a real container can show that — which is the reason the
 * pair lives here and not in a MockMvc class. The name is left alone deliberately: a renamed test
 * class reads as a lost class in the per-class suite comparison the migration is gated on, and a
 * tidier name is not worth spending that signal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RealServletContainer401IntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("cart_db")
          .withUsername("cart")
          .withPassword("cart");

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
    registry.add(
        "security.jwt.public-keys." + JwtTestKeys.KID_A, () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
  }

  /**
   * Path A — the two hand-written renderers that write through {@code response.getWriter()}. The
   * container stamps its default charset onto a writer response, so the wire carries the parameter
   * MockMvc normalises away. Measured on 3.5.16 and re-measured on Boot 4.1.1 / Tomcat 11 while
   * adding the 403 row below: unchanged on both. That the string survived the bump is the finding
   * this class exists to be able to state.
   */
  private static final String PATH_A_CONTENT_TYPE = "application/json;charset=ISO-8859-1";

  /**
   * Path B — everything rendered by the converter stack: controller bodies and every {@code
   * GlobalExceptionHandler} envelope. No charset parameter. The two paths genuinely differ, and
   * that difference is the invariant; the instinct to assert they are equal, or to "fix" A to match
   * B, is wrong on both counts while the renderers set no encoding of their own.
   */
  private static final String PATH_B_CONTENT_TYPE = "application/json";

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  private ResponseEntity<byte[]> get(String path, String authorization) {
    HttpHeaders headers = new HttpHeaders();
    if (authorization != null) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    return restTemplate.exchange(
        "http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
  }

  private static String contentType(ResponseEntity<byte[]> response) {
    String value = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    return value == null ? "<absent>" : value;
  }

  /**
   * Path A, the 403 half. Its MockMvc counterpart in {@code FrameworkErrorMappingIntegrationTest}
   * reads a normalised {@code application/json}, which may never be quoted as evidence of the
   * production string. {@code RestAccessDeniedHandler} writes through {@code getWriter()} exactly
   * as the entry point does, so the same container charset is expected — asserted here rather than
   * inferred from the 401, because identical code shape is an argument and not a measurement.
   */
  @Test
  void denied403_wireContentType_carriesTheContainerCharset() {
    String token = JwtTestKeys.mintRs256(7L, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);

    ResponseEntity<byte[]> response = get("/internal-denied", "Bearer " + token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(contentType(response))
        .as("path A writes via response.getWriter(); the container stamps its default charset")
        .isEqualTo(PATH_A_CONTENT_TYPE);
    assertThat(new String(response.getBody(), StandardCharsets.ISO_8859_1))
        .contains("\"error\":\"FORBIDDEN\"");
  }

  /**
   * Path B, error side: the advice-rendered envelope agrees with the converter stack, not with the
   * hand-written renderers. Asserting the inequality as well keeps the pair honest — if some future
   * change makes both paths carry the same string, this row fails and forces the class javadoc to
   * be rewritten rather than letting the distinction quietly evaporate.
   */
  @Test
  void adviceRendered404_wireContentType_matchesPathBNotPathA() {
    String token = JwtTestKeys.mintRs256(7L, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);

    ResponseEntity<byte[]> response = get("/api/v1/carts", "Bearer " + token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(contentType(response)).isEqualTo(PATH_B_CONTENT_TYPE);
    assertThat(contentType(response))
        .as(
            "the two rendering paths genuinely differ — read the class javadoc before 'fixing' this")
        .isNotEqualTo(PATH_A_CONTENT_TYPE);
  }

  /**
   * Path B, success side. Without it the pair could be satisfied by a service that renders every
   * body through the hand-written path, which is a different architecture and not the one measured.
   */
  @Test
  void authenticated200_wireContentType_hasNoCharsetParameter() {
    String token = JwtTestKeys.mintRs256(7L, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);

    ResponseEntity<byte[]> response = get("/api/v1/cart", "Bearer " + token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(contentType(response)).isEqualTo(PATH_B_CONTENT_TYPE);
  }

  @Test
  void tokenless401_carriesTheContainerContentTypeByteString_andTheFourFieldEnvelope() {
    ResponseEntity<byte[]> response =
        restTemplate.getForEntity("http://localhost:" + port + "/api/v1/cart", byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
        .as("the byte string a real client reads; the MockMvc rows pin a different one")
        .isEqualTo("application/json;charset=ISO-8859-1");

    // Decode with the charset the header advertises, then pin the whole body with only the
    // timestamp masked — key order and separators included, since these are the bytes on the wire.
    String body = new String(response.getBody(), StandardCharsets.ISO_8859_1);
    String masked = body.replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"<masked>\"");

    assertThat(masked)
        .isEqualTo(
            "{\"error\":\"UNAUTHORIZED\",\"message\":\"Authentication required\","
                + "\"timestamp\":\"<masked>\",\"path\":\"/api/v1/cart\"}");
    assertThat(masked)
        .as("masking must have matched — an unmasked timestamp means the envelope shape moved")
        .contains("<masked>");
  }
}
