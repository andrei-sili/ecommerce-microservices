package com.ecommerce.user;

import com.ecommerce.user.support.JwtTestKeys;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests against a real PostgreSQL.
 *
 * <p>By default it starts an ephemeral PostgreSQL via Testcontainers (started manually so the
 * lifecycle can be skipped when an external DB is supplied). If {@code EXTERNAL_DB_URL} is provided
 * (env or system property), it uses that already-running database instead.
 *
 * <p>If neither an external DB is configured nor a usable Docker environment is available, the
 * integration tests <strong>fail loudly</strong> (they never self-skip), so a misconfigured Docker
 * cannot masquerade as a green {@code "Tests run: 0"}. CI must run with a working Docker daemon (or
 * an external DB) so these tests actually execute.
 *
 * <p><strong>Config source.</strong> Every context here boots the SHIPPED {@code
 * src/main/resources/application.yml} and layers {@code src/test/resources/application-test.yml} on
 * top, via the {@code test} profile activated below. Before the S-shadow slice the test tree held
 * an {@code application.yml} of its own, which shadowed the shipped file outright — so the suite
 * asserted a configuration it had handed itself, and mutating the shipped jackson block left all
 * 124 tests green. Any {@code @SpringBootTest} in this service must extend this class (or activate
 * the profile itself); a context without it fails loudly on the shipped file's mandatory {@code
 * ${SPRING_DATASOURCE_URL}} placeholder rather than degrading quietly.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  private static final String EXTERNAL_DB_URL = resolve("EXTERNAL_DB_URL");
  private static final String EXTERNAL_DB_USERNAME = resolve("EXTERNAL_DB_USERNAME");
  private static final String EXTERNAL_DB_PASSWORD = resolve("EXTERNAL_DB_PASSWORD");

  private static final boolean USE_EXTERNAL = EXTERNAL_DB_URL != null && !EXTERNAL_DB_URL.isBlank();

  private static final PostgreSQLContainer<?> POSTGRES = startContainerIfNeeded();
  private static final Throwable CONTAINER_FAILURE = ContainerHolder.failure;

  @SuppressWarnings("resource")
  private static PostgreSQLContainer<?> startContainerIfNeeded() {
    if (USE_EXTERNAL) {
      return null;
    }
    try {
      PostgreSQLContainer<?> container =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withDatabaseName("user_db")
              .withUsername("user_svc")
              .withPassword("test_pw");
      container.start();
      return container;
    } catch (Throwable t) {
      ContainerHolder.failure = t;
      return null;
    }
  }

  /**
   * Wires the runtime-generated RSA keys so every context test boots with valid dual-accept
   * material (user is the signer → it mounts the private key too). Two kids are configured for the
   * rotation-coexistence proof. {@code accepted-algs} comes from the test overlay (HS256,RS256);
   * RS256-only tests narrow it with their own {@code @DynamicPropertySource}.
   *
   * <p>The two {@code JWT_*_PATH} entries below are not redundant with the three above them. Now
   * that the SHIPPED yml is the base config, its {@code private-key-path: ${JWT_PRIVATE_KEY_PATH}}
   * and {@code public-keys.user-rs256-2026-07: ${JWT_PUBLIC_KEY_PATH}} are real placeholders that
   * must resolve. The scalar is also overridden directly, but a {@code Map} binding collects keys
   * from EVERY property source rather than taking the highest-precedence one, so the shipped kid
   * entry is bound regardless and would fail the context on an unresolvable placeholder. Supplying
   * the env names keeps the shipped key-material wiring shape exercised instead of bypassed — and
   * the shipped kid is {@link JwtTestKeys#KID_A}, so both routes land on the same PEM.
   */
  @DynamicPropertySource
  static void jwtKeyProperties(DynamicPropertyRegistry registry) {
    registry.add("JWT_PRIVATE_KEY_PATH", () -> JwtTestKeys.PRIVATE_KEY_PATH_A);
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("security.jwt.private-key-path", () -> JwtTestKeys.PRIVATE_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_B + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_B);
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    if (USE_EXTERNAL) {
      registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
      registry.add("spring.datasource.username", () -> EXTERNAL_DB_USERNAME);
      registry.add("spring.datasource.password", () -> EXTERNAL_DB_PASSWORD);
    } else if (POSTGRES != null) {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
    } else {
      // No DB available: provide placeholders so static wiring does not NPE. requireDatabase()
      // fails the run loudly in @BeforeAll before any test executes.
      registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:1/none");
      registry.add("spring.datasource.username", () -> "none");
      registry.add("spring.datasource.password", () -> "none");
    }
  }

  @BeforeAll
  static void requireDatabase() {
    if (!USE_EXTERNAL && POSTGRES == null) {
      throw new IllegalStateException(
          "Integration tests require a database but none is available: Testcontainers could not "
              + "start a Docker container and no EXTERNAL_DB_URL was provided",
          CONTAINER_FAILURE);
    }
  }

  private static String resolve(String key) {
    String fromEnv = System.getenv(key);
    return fromEnv != null ? fromEnv : System.getProperty(key);
  }

  private static final class ContainerHolder {
    static Throwable failure;
  }
}
