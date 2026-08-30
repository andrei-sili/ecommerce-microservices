package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ecommerce.order.support.JwtTestKeys;
import com.ecommerce.order.support.TestJwt;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * F3's in-suite guardian: {@code management.endpoint.health.group.readiness.include:
 * readinessState,db} in the SHIPPED yml must keep {@code db} in the readiness group.
 *
 * <p>Why this class exists at all. Measured on this service before it was written: deleting {@code
 * db} from that line leaves the ENTIRE suite green at full-suite scope. The line was read but
 * unasserted — confirmed by the paired control, flipping {@code probes.enabled} to false in the
 * same file, which fails startup naming {@code readinessState}. So the config was live and simply
 * had no guardian. Drop {@code db} again and this class is the thing that goes red.
 *
 * <p><strong>Scope, stated precisely so nobody reads more into a green than it earns.</strong> This
 * pins GROUP MEMBERSHIP — that a dead database drags readiness down — and nothing about the TIMING
 * of that detection. {@code stop()} closes the socket and the peer answers RST, which surfaces
 * immediately; the K8s pod-loss failure mode is the opposite (the IP vanishes with no RST, and only
 * pgjdbc's {@code socketTimeout} bounds the read). A green here is therefore NOT evidence that
 * pod-loss detection works — that remains the job of {@code infra/scripts/podloss-readiness.sh}.
 * Recorded because {@code .claude/rules/testing.md} warns that a {@code container.stop()} probe
 * passes even without the socketTimeout fix, and a class named for readiness is exactly where
 * someone would later mistake one for the other.
 *
 * <p>The class carries its own container because it stops it; the shared base's container is reused
 * by every other integration class. It sets no {@code management.*} property: B9(a) requires the
 * test config to declare none, so the group being asserted can only have come from the shipped
 * file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadinessDbDownIntegrationTest {

  private static final PostgreSQLContainer<?> OWN_POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("order_db")
          .withUsername("order")
          .withPassword("order");

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
    // Survives the container being stopped mid-test: once it is down getJdbcUrl() would throw, and
    // a health check must still be able to ATTEMPT a connection and fail, not blow up resolving a
    // property.
    registry.add(
        "spring.datasource.url",
        () ->
            OWN_POSTGRES.isRunning()
                ? OWN_POSTGRES.getJdbcUrl()
                : "jdbc:postgresql://unused:5432/unused");
    registry.add("spring.datasource.username", OWN_POSTGRES::getUsername);
    registry.add("spring.datasource.password", OWN_POSTGRES::getPassword);
    registry.add("security.jwt.secret", () -> TestJwt.SECRET);
  }

  /**
   * The shipped yml declares the production kid as {@code user-rs256-2026-07:
   * ${JWT_PUBLIC_KEY_PATH}}; because this class reads the shipped file for real (that is the whole
   * point), the placeholder must resolve or no context here can start.
   */
  @DynamicPropertySource
  static void jwtKeys(DynamicPropertyRegistry registry) {
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
  }

  @Test
  void readinessFollowsTheDatabase_whileLivenessDoesNot() throws Exception {
    // Positive control first: without it, a DOWN below could equally mean "readiness was never UP".
    assertThat(status("/actuator/health/readiness")).isEqualTo(200);
    assertThat(body("/actuator/health/readiness")).isEqualTo("{\"status\":\"UP\"}");

    OWN_POSTGRES.stop();

    int readiness = pollUntilNot200("/actuator/health/readiness", Duration.ofSeconds(30));
    assertThat(readiness)
        .as(
            "readiness must follow the database down; if this is still 200, 'db' has left the"
                + " readiness group and Kong would keep routing traffic to a DB-dead pod")
        .isEqualTo(503);
    assertThat(body("/actuator/health/readiness")).isEqualTo("{\"status\":\"DOWN\"}");

    // Liveness is livenessState-only and must NOT follow the database: a DOWN here would make K8s
    // restart a pod whose own process is healthy, turning a dependency outage into a crash loop.
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
