package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ecommerce.cart.support.JwtTestKeys;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The in-suite guardian for {@code management.endpoint.health.group.readiness.include:
 * readinessState,db} in the SHIPPED yml: a dead database must drag readiness down, and must not
 * drag liveness down with it.
 *
 * <p>Why the class exists. That configuration line is read but was entirely unasserted — deleting
 * {@code db} from it leaves every other test in this module green, because with {@code
 * show-details} unset the readiness endpoint renders {@code {"status":"UP"}} whatever the group
 * contains. Nothing could observe the difference, so the line's only guardian was a person watching
 * a running fleet. Drop {@code db} again and this class is what goes red.
 *
 * <p><b>Scope, stated precisely so a green here is not read as more than it earns.</b> This pins
 * GROUP MEMBERSHIP — that a dead database reaches readiness — and says nothing about the TIMING of
 * that detection. Stopping the container closes the socket and the peer answers with a reset, which
 * surfaces immediately. The failure mode that actually matters in a cluster is the opposite one:
 * the database's address vanishes with no reset at all, the pooled connection still looks alive,
 * and only the driver's socket read timeout bounds the wait. A probe built on {@code stop()} passes
 * whether or not that timeout is configured, so this row is NOT evidence that pod-loss detection
 * works. That remains the job of the operational pod-loss procedure. Written down here, in the
 * class itself, because a class named for readiness is exactly where someone would later make that
 * substitution.
 *
 * <p>The class carries its own container because it stops it; every other integration class in this
 * module shares one. It sets no {@code management.*} property of its own, so the group being
 * asserted can only have come from the shipped file — cart has no test-side configuration to shadow
 * it with.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadinessDbDownIntegrationTest {

  private static final org.testcontainers.containers.PostgreSQLContainer<?> OWN_POSTGRES =
      new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("cart_db")
          .withUsername("cart")
          .withPassword("cart");

  @Autowired private MockMvc mockMvc;

  @BeforeAll
  static void start() {
    OWN_POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    if (OWN_POSTGRES.isRunning()) {
      OWN_POSTGRES.stop();
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    // Must survive the container being stopped mid-test: once it is down getJdbcUrl() throws, and a
    // health check has to be able to ATTEMPT a connection and fail rather than blow up resolving a
    // property, which would fail the test for the wrong reason.
    registry.add(
        "spring.datasource.url",
        () ->
            OWN_POSTGRES.isRunning()
                ? OWN_POSTGRES.getJdbcUrl()
                : "jdbc:postgresql://unused:5432/unused");
    registry.add("spring.datasource.username", OWN_POSTGRES::getUsername);
    registry.add("spring.datasource.password", OWN_POSTGRES::getPassword);
  }

  /**
   * The shipped yml declares the production key id against an environment placeholder, and this
   * class reads the shipped file for real — that being the entire point — so the placeholder has to
   * resolve or no context here can start at all.
   */
  @DynamicPropertySource
  static void jwtKeys(DynamicPropertyRegistry registry) {
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys." + JwtTestKeys.KID_A, () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
  }

  @Test
  void readinessFollowsTheDatabase_whileLivenessDoesNot() throws Exception {
    // Positive control first: without it, the DOWN below could equally mean readiness was never UP,
    // and the assertion would pass for a reason that has nothing to do with the database.
    assertThat(status("/actuator/health/readiness")).isEqualTo(200);
    assertThat(body("/actuator/health/readiness")).isEqualTo("{\"status\":\"UP\"}");

    OWN_POSTGRES.stop();

    int readiness = pollUntilNot200("/actuator/health/readiness", Duration.ofSeconds(30));
    assertThat(readiness)
        .as(
            "readiness must follow the database down; a 200 here means 'db' has left the readiness"
                + " group and the edge would keep routing traffic to an instance with no database")
        .isEqualTo(503);
    assertThat(body("/actuator/health/readiness")).isEqualTo("{\"status\":\"DOWN\"}");

    // Liveness is livenessState-only and must NOT follow the database: a DOWN here would have the
    // orchestrator restart a process that is perfectly healthy, turning a dependency outage into a
    // crash loop across every replica at once.
    assertThat(status("/actuator/health/liveness")).isEqualTo(200);
    assertThat(body("/actuator/health/liveness")).isEqualTo("{\"status\":\"UP\"}");
  }

  private int status(String path) throws Exception {
    return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
  }

  private String body(String path) throws Exception {
    return mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
  }

  private int pollUntilNot200(String path, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    int last = 200;
    while (System.nanoTime() < deadline) {
      last = status(path);
      if (last != 200) {
        return last;
      }
      Thread.sleep(250);
    }
    return last;
  }
}
