package com.ecommerce.cart.support;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for full-stack integration tests against a real PostgreSQL: Flyway runs the real migrations,
 * Hibernate validates the entities, and HTTP flows are exercised through {@code MockMvc}.
 *
 * <p>The container is started manually in {@code @BeforeAll}. If Docker is unavailable or
 * incompatible, {@code start()} fails loudly and the suite goes red — the integration tests never
 * self-skip, so a misconfigured Docker cannot masquerade as a green {@code "Tests run: 0"}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES =
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
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            POSTGRES.isRunning() ? POSTGRES.getJdbcUrl() : "jdbc:postgresql://unused:5432/unused");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("security.jwt.secret", () -> TestJwt.SECRET);
  }

  /**
   * Wires the runtime-generated RSA public keys so every full-context test boots with valid
   * dual-accept material. The KID_A entry overrides the {@code ${JWT_PUBLIC_KEY_PATH}} placeholder
   * carried by the main yaml; KID_B adds a second key for the rotation-coexistence proof. {@code
   * accepted-algs} stays at the yml default (HS256,RS256); the allowlist-contraction suites
   * override it with their own {@code @DynamicPropertySource}.
   */
  @DynamicPropertySource
  static void jwtPublicKeys(DynamicPropertyRegistry registry) {
    registry.add(
        "security.jwt.public-keys." + JwtTestKeys.KID_A, () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys." + JwtTestKeys.KID_B, () -> JwtTestKeys.PUBLIC_KEY_PATH_B);
  }
}
