package com.ecommerce.order.support;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
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
 *
 * <p>{@code @ActiveProfiles("test")} is load-bearing, not decoration: it makes the contexts read
 * the SHIPPED {@code src/main/resources/application.yml} and layer {@code application-test.yml} on
 * top. Before this, {@code src/test/resources/application.yml} shadowed the shipped file outright
 * and the suite asserted a configuration it handed itself — emptying the shipped file left all 135
 * tests green. Remove the profile (or reintroduce a test {@code application.yml}) and every
 * casing/inclusion pin in this service goes blind again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("order_db")
          .withUsername("order")
          .withPassword("order");

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
   * Wires the runtime-generated RSA public keys so every context boots with valid dual-accept
   * material (Order validates only — no private key). Two kids are configured for the
   * rotation-coexistence proof. {@code accepted-algs} stays at the overlay default (HS256,RS256);
   * RS256-only / HS256-only suites override it with their own {@code @DynamicPropertySource}.
   *
   * <p>{@code JWT_PUBLIC_KEY_PATH} is supplied because the shipped {@code application.yml} is now
   * genuinely read: it declares the production kid with {@code user-rs256-2026-07:
   * ${JWT_PUBLIC_KEY_PATH}}, and Boot merges map entries across property sources, so an
   * unresolvable placeholder there would fail every context. {@link JwtTestKeys#KID_A} IS that kid,
   * so the shipped entry and the registration below name the same key and the map still holds
   * exactly the two test kids.
   */
  @DynamicPropertySource
  static void jwtKeyProperties(DynamicPropertyRegistry registry) {
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_B + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_B);
  }
}
