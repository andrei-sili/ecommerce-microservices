package com.ecommerce.user;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
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
 * integration tests are <strong>skipped</strong> (JUnit assumption) rather than failing the build,
 * so the unit tests still gate {@code mvn test}. CI must run with a working Docker daemon (or an
 * external DB) so these tests actually execute.
 */
@SpringBootTest
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
      // No DB available: provide placeholders so context init is not attempted before the
      // assumption below short-circuits the tests.
      registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:1/none");
      registry.add("spring.datasource.username", () -> "none");
      registry.add("spring.datasource.password", () -> "none");
    }
  }

  @BeforeAll
  static void requireDatabase() {
    Assumptions.assumeTrue(
        USE_EXTERNAL || POSTGRES != null,
        () ->
            "Skipping integration tests: no usable Docker environment for Testcontainers and no "
                + "EXTERNAL_DB_URL provided"
                + (CONTAINER_FAILURE != null ? " (" + CONTAINER_FAILURE.getMessage() + ")" : ""));
  }

  private static String resolve(String key) {
    String fromEnv = System.getenv(key);
    return fromEnv != null ? fromEnv : System.getProperty(key);
  }

  private static final class ContainerHolder {
    static Throwable failure;
  }
}
