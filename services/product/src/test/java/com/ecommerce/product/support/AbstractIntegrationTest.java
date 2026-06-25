package com.ecommerce.product.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for full-stack integration tests against a real PostgreSQL: Flyway runs the real migrations,
 * Hibernate validates the entities, and HTTP flows are exercised through {@code MockMvc}.
 *
 * <p>The container is started manually (not via the {@code @Testcontainers} extension) so the
 * Docker-availability assumption can run first and skip the class cleanly on environments without a
 * compatible Docker daemon, instead of failing the build.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("product_db")
          .withUsername("product")
          .withPassword("product");

  @BeforeAll
  static void ensureDocker() {
    Assumptions.assumeTrue(
        isDockerAvailable(), "Docker is not available/compatible — skipping integration tests");
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
  }

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable t) {
      return false;
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
}
