package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ecommerce.product.support.JwtTestKeys;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * F3's in-suite guardian: {@code management.endpoint.health.group.readiness.include:
 * readinessState,db} in the SHIPPED yml must keep {@code db} in the readiness group.
 *
 * <p>Why it exists. product has no {@code src/test/resources}, so every suite here already reads
 * the shipped file — but reading is not asserting. Before this class, deleting {@code db} from that
 * line left the whole suite green: with {@code show-details} unset (the shipped posture), {@code
 * /actuator/health/readiness} renders {@code {"status":"UP"}} whatever the group contains, so
 * {@code ActuatorPermitMatrixIT}'s byte-exact body cannot tell a two-member group from a one-member
 * one. Drop {@code db} again and this class is what goes red.
 *
 * <p><strong>Scope, stated so nobody reads more into a green than it earns.</strong> This pins
 * GROUP MEMBERSHIP — that a dead database drags readiness down — and nothing about the TIMING of
 * that detection. {@code stop()} closes the socket and the peer answers RST, which surfaces at
 * once; the Kubernetes pod-loss failure mode is the opposite, the IP vanishes with no RST and only
 * pgjdbc's {@code socketTimeout} bounds the read. A green here is therefore NOT evidence that
 * pod-loss detection works: a {@code stop()}-based probe passes whether or not the socketTimeout
 * configuration is in place. That remains the job of {@code infra/scripts/podloss-readiness.sh}.
 * Recorded in the class itself because a class named for readiness is exactly where someone would
 * later make that inference.
 *
 * <p>The class carries its OWN container because it stops it. The base class's container is shared
 * across every other integration class in this JVM, so stopping that one would take unrelated
 * suites down with it and the failure would be attributed to them.
 *
 * <p>It sets no {@code management.*} property: B9(a) requires the test configuration to declare
 * none, so the group being asserted here can only have come from the shipped file.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadinessDbDownIT {

  private static final PostgreSQLContainer<?> OWN_POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("product_db")
          .withUsername("product")
          .withPassword("product");

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
  static void properties(DynamicPropertyRegistry registry) {
    // Must survive the container being stopped mid-test: once it is down getJdbcUrl() throws, and
    // the health check has to be able to ATTEMPT a connection and fail rather than blow up while
    // resolving a property.
    registry.add(
        "spring.datasource.url",
        () ->
            OWN_POSTGRES.isRunning()
                ? OWN_POSTGRES.getJdbcUrl()
                : "jdbc:postgresql://unused:5432/unused");
    registry.add("spring.datasource.username", OWN_POSTGRES::getUsername);
    registry.add("spring.datasource.password", OWN_POSTGRES::getPassword);
    // The shipped yml declares the production kid as user-rs256-2026-07: ${JWT_PUBLIC_KEY_PATH},
    // and this class reads that file for real — the whole point — so the placeholder must resolve
    // or no context here can start.
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("security.internal-api-key", () -> "test-internal-api-key");
    // @EnableScheduling is UNCONDITIONAL on ProductApplication, so this context holds a live
    // scheduling lifecycle. Push the sweeper past the heat death of the test so it never fires
    // against the database this class is about to kill.
    registry.add("reservation.sweeper.delay-ms", () -> String.valueOf(Long.MAX_VALUE / 2));
  }

  @Test
  void readinessFollowsTheDatabase_whileLivenessDoesNot() throws Exception {
    // Positive control first: without it a DOWN below could equally mean "readiness was never UP".
    assertThat(status("/actuator/health/readiness")).isEqualTo(200);
    assertThat(body("/actuator/health/readiness")).isEqualTo("{\"status\":\"UP\"}");

    OWN_POSTGRES.stop();

    int readiness = pollUntilNot200("/actuator/health/readiness", Duration.ofSeconds(30));
    assertThat(readiness)
        .as(
            "readiness must follow the database down; a 200 here means 'db' has left the readiness"
                + " group and Kong would keep routing catalog traffic to a DB-dead pod")
        .isEqualTo(503);
    assertThat(body("/actuator/health/readiness")).isEqualTo("{\"status\":\"DOWN\"}");

    // Liveness is livenessState-only and must NOT follow the database: a DOWN here would make
    // Kubernetes restart a pod whose own process is fine, turning a dependency outage into a crash
    // loop.
    assertThat(status("/actuator/health/liveness")).isEqualTo(200);
    assertThat(body("/actuator/health/liveness")).isEqualTo("{\"status\":\"UP\"}");
  }

  private int status(String path) throws Exception {
    return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
  }

  private String body(String path) throws Exception {
    MvcResult result = mockMvc.perform(get(path)).andReturn();
    return result.getResponse().getContentAsString();
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
