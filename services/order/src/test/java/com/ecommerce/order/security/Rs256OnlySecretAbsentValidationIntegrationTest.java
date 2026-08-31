package com.ecommerce.order.security;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.order.config.JwtProperties;
import com.ecommerce.order.support.JwtTestKeys;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase-3 production shape: {@code accepted-algs} rides the SHIPPED yaml default (RS256) and {@code
 * security.jwt.secret} is ENTIRELY ABSENT — the exact posture the deploy ships.
 *
 * <p><b>This class deliberately does NOT carry {@code @ActiveProfiles("test")}</b>, so it reads
 * {@code src/main/resources/application.yml} alone. That is the whole point and it is the one thing
 * not to "tidy up": every other suite in this service inherits the test overlay, which supplies
 * both a {@code secret} and {@code accepted-algs: HS256,RS256} so the dual-accept matrix can
 * exercise both branches. None of them can therefore prove the shipped posture. {@link
 * Rs256OnlyValidationIntegrationTest} hand-replays {@code accepted-algs=RS256} through a
 * {@code @DynamicPropertySource} and {@code JwtFailFastTest} builds {@link
 * com.ecommerce.order.config.JwtProperties} by hand with no Spring context at all — both prove the
 * MECHANISM, neither proves the SHIPPED VALUE.
 *
 * <p>The two phase-3 edits are detected by DIFFERENT rows, and the difference is measured, not
 * assumed:
 *
 * <ul>
 *   <li><b>Allowlist re-widened</b> (shipped default gains HS256) → {@code loadHmacKey} demands the
 *       now-absent secret and the CONTEXT FAILS TO START. A startup red, not an assertion red —
 *       stated plainly because that is what it is; the cause names the posture: {@code
 *       IllegalStateException: JWT_SECRET must be at least 32 bytes for HS256 (required because
 *       HS256 is in JWT_ACCEPTED_ALGS)}.
 *   <li><b>Secret resurrected</b> (a {@code secret:} inserted into the shipped yaml) → the two HTTP
 *       rows below stay GREEN, measured: with HS256 off the allowlist the secret is never read.
 *       Only {@link #shippedJwtProperties_haveNoSecret_andRs256OnlyAllowlist()} catches it. Do not
 *       delete that row on the grounds that the HTTP rows "already cover" the posture.
 * </ul>
 *
 * <p>Order-specific boot needs, registered because the shipped yaml binds them with no default —
 * without these the context dies before either assertion runs, which would turn a posture failure
 * into an unrelated startup error: {@code spring.datasource.*} and {@code
 * clients.product.internal-api-key} ({@code ${INTERNAL_API_KEY}}). {@code JWT_PUBLIC_KEY_PATH} is
 * registered rather than the {@code public-keys} map itself, so the SHIPPED map entry (kid {@code
 * user-rs256-2026-07}) is the one under test. No {@code secret} and no {@code accepted-algs} are
 * registered here — registering either would silently destroy the pin.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Rs256OnlySecretAbsentValidationIntegrationTest {

  private static final long SUBJECT = 7L;
  private static final String ORDERS_PATH = "/api/v1/orders";

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
  static void properties(DynamicPropertyRegistry registry) {
    // The SHIPPED yaml binds exactly five placeholders with no default; all five are below.
    // Enumerated from the file rather than discovered one context failure at a time:
    //   grep -nE '\$\{[A-Z_]+\}' src/main/resources/application.yml
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("INTERNAL_API_KEY", () -> "test-internal-api-key");

    // Dropping @ActiveProfiles also drops the overlay's scheduling/listener switches, and the
    // shipped defaults turn both back ON: SchedulingConfig is @ConditionalOnProperty with
    // matchIfMissing=true, so OutboxRelay's @Scheduled fires every second, and listener containers
    // auto-start — both against the compose hostname `rabbitmq`, unresolvable here. Not a guess:
    // before these two lines the run logged `Attempting to connect to: [rabbitmq:5672]` and
    // `java.net.UnknownHostException: rabbitmq`. Only the JWT posture is under test here, and no
    // assertion in this class depends on the shipped value of either key.
    registry.add("app.scheduling.enabled", () -> "false");
    registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtProperties jwtProperties;

  /**
   * The bean-level half. Without it the "secret is absent" claim is UNFALSIFIABLE: measured on this
   * very class, inserting a {@code secret:} into the shipped yaml leaves both HTTP rows green
   * (140/140 passed), because with HS256 off the allowlist {@code loadHmacKey} returns before
   * reading it. So the HTTP rows pin the ALLOWLIST; this row is the only thing that pins the
   * SECRET'S ABSENCE.
   */
  @Test
  void shippedJwtProperties_haveNoSecret_andRs256OnlyAllowlist() {
    assertNull(
        jwtProperties.secret(),
        "the shipped application.yml must bind NO security.jwt.secret — phase-3 deleted it"
            + " (fail-closed, D3); a resurrected secret is invisible to every HTTP assertion in"
            + " this class");
    assertEquals(
        List.of("RS256"),
        jwtProperties.acceptedAlgs(),
        "the shipped allowlist must remain RS256-only");
  }

  @Test
  void rs256Token_accepted_whenSecretPropertyAbsent() throws Exception {
    String token = JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);

    mockMvc
        .perform(get(ORDERS_PATH).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        // §4h(3): exact Content-Type ahead of any body assertion, so a representation drift names
        // the media type rather than reporting a missing JSON path.
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        // snake_case, from the shipped jackson block this context also reads for real.
        .andExpect(jsonPath("$.total_elements", is(0)));
  }

  @Test
  void freshHs256Token_rejected401_whenSecretPropertyAbsent() throws Exception {
    String token = JwtTestKeys.mintHs256(SUBJECT);

    mockMvc
        .perform(get(ORDERS_PATH).header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.message", is("Authentication required")))
        .andExpect(jsonPath("$.path", is(ORDERS_PATH)))
        .andExpect(jsonPath("$.timestamp", notNullValue()));
  }
}
