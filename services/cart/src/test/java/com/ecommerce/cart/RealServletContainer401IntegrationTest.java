package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.cart.support.JwtTestKeys;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

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
