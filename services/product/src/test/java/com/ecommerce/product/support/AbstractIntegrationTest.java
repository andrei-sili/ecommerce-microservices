package com.ecommerce.product.support;

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

  /** Internal API key wired into the test context; reservation tests send it via the header. */
  public static final String INTERNAL_API_KEY = "test-internal-api-key";

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
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            POSTGRES.isRunning() ? POSTGRES.getJdbcUrl() : "jdbc:postgresql://unused:5432/unused");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("security.jwt.secret", () -> TestJwt.SECRET);
    registry.add("security.internal-api-key", () -> INTERNAL_API_KEY);
    // Prevent the sweeper from firing automatically during tests: set both the initial delay and
    // the fixed delay to ~292 million years so the @Scheduled trigger never runs. Tests that need
    // the sweep logic call ReservationSweeper.sweep() directly.
    registry.add("reservation.sweeper.delay-ms", () -> String.valueOf(Long.MAX_VALUE / 2));
  }
}
