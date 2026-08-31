package com.ecommerce.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ecommerce.user.support.JwtTestKeys;
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
 * Contract F3(a), user's row: {@code management.endpoint.health.group.readiness.include:
 * readinessState,db} in the SHIPPED yml must keep {@code db} in the readiness group.
 *
 * <p><strong>Why it exists.</strong> Reading the shipped file is not asserting it. Before this
 * class, deleting {@code db} from that line left the whole suite green: with {@code show-details}
 * unset — the shipped posture — {@code /actuator/health/readiness} renders {@code {"status":"UP"}}
 * whatever the group contains, so {@code ActuatorPermitMatrixIntegrationTest}'s byte-exact body and
 * its 15-byte length check cannot tell a two-member group from a one-member one. Both are correct
 * and both are blind to this. Drop {@code db} again and this class is what goes red.
 *
 * <p>The consequence on THIS service is the fleet's worst. Kong's active probe reads {@code
 * /actuator/health}; a user-service whose database is gone but whose readiness says UP keeps the
 * upstream in rotation, so every {@code /api/v1/auth/*} request — the login path for the entire
 * platform — is routed to a pod that cannot authenticate anyone, instead of failing over.
 *
 * <p><strong>Scope, stated so nobody reads more into a green than it earns.</strong> This pins
 * GROUP MEMBERSHIP — that a dead database drags readiness down — and nothing about the TIMING of
 * that detection. {@code stop()} closes the socket and the peer answers RST, which surfaces at
 * once; the Kubernetes pod-loss failure mode is the opposite, the IP vanishes with no RST and only
 * pgjdbc's {@code socketTimeout} bounds the read. A green here is therefore NOT evidence that
 * pod-loss detection works: a {@code stop()}-based probe passes whether or not that configuration
 * is in place. That remains the job of {@code infra/scripts/podloss-readiness.sh} (contract F3(b),
 * owned by devops). Recorded in the class itself because a class named for readiness is exactly
 * where someone would later make that inference.
 *
 * <p>The class carries its OWN container because it stops it. {@link AbstractIntegrationTest}'s
 * container is shared across every other integration class in this JVM, so stopping that one would
 * take unrelated suites down with it and the failure would be attributed to them. That is also why
 * this class does not extend the base — but it DOES activate the {@code test} profile, or it would
 * boot without the JWT material the shipped file's placeholders demand.
 *
 * <p>It sets no {@code management.*} property: B9(a) requires the test configuration to declare
 * none, so the group asserted here can only have come from {@code
 * src/main/resources/application.yml}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadinessDbDownIntegrationTest {

  private static final PostgreSQLContainer<?> OWN_POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("user_db")
          .withUsername("user_svc")
          .withPassword("test_pw");

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
    // The shipped yml declares private-key-path and the production kid as placeholders, and this
    // class reads that file for real — the whole point — so they must resolve or no context starts.
    registry.add("JWT_PRIVATE_KEY_PATH", () -> JwtTestKeys.PRIVATE_KEY_PATH_A);
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("security.jwt.private-key-path", () -> JwtTestKeys.PRIVATE_KEY_PATH_A);
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    // Same reason as AbstractIntegrationTest: OS env outranks profile yml for admin.bootstrap.*,
    // and a test run must never seed a real ADMIN account on the auth service.
    registry.add("admin.bootstrap.email", () -> "");
    registry.add("admin.bootstrap.password", () -> "");
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
                + " group and Kong would keep routing every /api/v1/auth/* request to a pod that"
                + " cannot authenticate anyone")
        .isEqualTo(503);
    assertThat(body("/actuator/health/readiness")).isEqualTo("{\"status\":\"DOWN\"}");

    // Liveness is livenessState-only and must NOT follow the database: a DOWN here would make
    // Kubernetes restart a pod whose own process is fine, turning a dependency outage into a crash
    // loop on the service every other service authenticates against.
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
