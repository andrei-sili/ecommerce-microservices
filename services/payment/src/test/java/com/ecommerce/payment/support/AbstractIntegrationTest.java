package com.ecommerce.payment.support;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for full-stack integration tests against a real PostgreSQL. Flyway runs the real migrations,
 * Hibernate validates the entities, and HTTP flows are exercised through MockMvc. The RabbitMQ
 * relay ({@code OutboxRelay}) is mocked in concrete tests so no broker is required.
 *
 * <p>{@code @ActiveProfiles("test")} is load-bearing, not cosmetic. The suite's config used to live
 * in {@code src/test/resources/application.yml}, which shadowed the shipped file outright, so every
 * serialization assertion here validated a configuration the suite handed itself. The overlay is
 * now {@code application-test.yml} and the SHIPPED {@code application.yml} is the base document. A
 * context root that forgets this annotation fails loudly rather than quietly: without the overlay
 * the shipped {@code ${SPRING_DATASOURCE_URL}} and RabbitMQ host have no test values.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payment_db")
          .withUsername("payment")
          .withPassword("payment");

  @BeforeAll
  static void startContainer() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            POSTGRES.isRunning() ? POSTGRES.getJdbcUrl() : "jdbc:postgresql://unused:5432/unused");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  /**
   * Wires the runtime-generated RSA public keys + the legacy HS256 secret so every context boots
   * with valid dual-accept material. Two kids are configured for the rotation-coexistence proof.
   * {@code accepted-algs} stays at the test-yml default (HS256,RS256); the allowlist-contraction
   * suites override it with their own {@code @DynamicPropertySource}.
   */
  @DynamicPropertySource
  static void jwtKeyProperties(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.secret", () -> JwtTestKeys.SECRET);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_B + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_B);
  }
}
