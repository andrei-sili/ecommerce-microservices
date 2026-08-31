package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.product.support.JwtTestKeys;
import com.ecommerce.product.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Wave 5c metrics: the Prometheus scrape endpoint must be reachable without auth (a ClusterIP
 * scraper carries no JWT), while the JWT/ADMIN authorization on the business API stays intact.
 *
 * <p>Runs against a real embedded Tomcat ({@code RANDOM_PORT}) + real Postgres so the full Spring
 * Security filter chain and the {@code WebMvcMetricsFilter} are exercised end-to-end, not mocked.
 * {@link AutoConfigureMetrics} is required because {@code @SpringBootTest} disables the metrics
 * registry by default, which would otherwise leave {@code /actuator/prometheus} unregistered.
 *
 * <p>{@link AutoConfigureTestRestTemplate} is required from Boot 4: {@code RANDOM_PORT} alone no
 * longer registers a {@code TestRestTemplate} bean, so without it the injection below fails the
 * context outright. Loud, but not obvious — the capability moved into {@code
 * spring-boot-resttestclient} along with the type itself and must now be asked for by name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@AutoConfigureTestRestTemplate
class PrometheusMetricsIT {

  private static final String PROMETHEUS_PATH = "/actuator/prometheus";

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
    // Phase-3 flipped the yml default to RS256-only; this suite exercises legacy HS256 tokens, so
    // pin the dual allowlist explicitly for its baseline.
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
    // Override the yml public-key placeholder with a runtime-generated key so the dual-accept
    // context boots (RS256 stays enabled); this suite only exercises HS256 tokens.
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("security.internal-api-key", () -> "test-internal-api-key");
    registry.add("reservation.sweeper.delay-ms", () -> String.valueOf(Long.MAX_VALUE / 2));
  }

  @Autowired private TestRestTemplate restTemplate;

  @BeforeEach
  void useJdkHttpClient() {
    // The legacy HttpURLConnection throws HttpRetryException on a 401 with a request body
    // ("cannot retry ... in streaming mode"); java.net.http.HttpClient returns the 401 as a
    // normal response, so the auth-negative assertions can inspect status + body.
    restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
  }

  @Test
  void prometheusScrape_withoutAuth_returns200PrometheusText() {
    // Warm up the http.server.requests timer with one real request before scraping.
    restTemplate.getForEntity("/api/v1/products?page=0&size=20", String.class);

    ResponseEntity<String> response = restTemplate.getForEntity(PROMETHEUS_PATH, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    MediaType contentType = response.getHeaders().getContentType();
    assertThat(contentType).isNotNull();
    assertThat(contentType.toString()).contains("text/plain");

    String body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body).contains("# HELP");
    assertThat(body).contains("jvm_");
    assertThat(body).contains("http_server_requests");
  }

  @Test
  void prometheusScrape_isNotUnderApiV1() {
    assertThat(PROMETHEUS_PATH).doesNotStartWith("/api/v1");
    // The metrics endpoint is not mounted under the versioned business namespace.
    ResponseEntity<String> viaApiV1 =
        restTemplate.getForEntity("/api/v1/actuator/prometheus", String.class);
    assertThat(viaApiV1.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(viaApiV1.getBody()).doesNotContain("# HELP");
  }

  @Test
  void protectedWrite_withoutAuth_stillUnauthorized() {
    ResponseEntity<String> response = postProduct(null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).contains("UNAUTHORIZED");
  }

  @Test
  void protectedWrite_withMalformedToken_stillUnauthorized() {
    ResponseEntity<String> response = postProduct("Bearer not-a-real-jwt");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).contains("UNAUTHORIZED");
  }

  @Test
  void protectedWrite_withNonAdminToken_stillForbidden() {
    String userToken = TestJwt.bearer(TestJwt.token("42", List.of("USER")));
    ResponseEntity<String> response = postProduct(userToken);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).contains("FORBIDDEN");
  }

  private ResponseEntity<String> postProduct(String authorization) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (authorization != null) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    String body =
        "{\"sku\":\"METRICS-1\",\"name\":\"X\",\"price\":1.00,\"currency\":\"EUR\","
            + "\"category_id\":1,\"stock_quantity\":1}";
    return restTemplate.exchange(
        "/api/v1/products", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }
}
