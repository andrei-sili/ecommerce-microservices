package com.ecommerce.payment;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.config.JwtProperties;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.support.JwtTestKeys;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase-3 production shape: {@code accepted-algs} rides the SHIPPED yaml default (RS256) and the
 * {@code security.jwt.secret} property is ENTIRELY ABSENT - the exact posture the deploy ships.
 * Same pin product and cart have carried since {@code f7e549c}; payment could not host it until the
 * test-config shadow became a profile overlay, because the shadow supplied its own {@code
 * accepted-algs} to every context.
 *
 * <p><b>This class deliberately carries no {@code @ActiveProfiles("test")}.</b> That is the
 * mechanism, not an oversight: without the overlay it reads {@code application.yml} alone, so the
 * shipped allowlist default is the one under test. Every other suite here injects a test secret and
 * an allowlist via {@code AbstractIntegrationTest} plus the overlay, so none of them can notice the
 * shipped default changing.
 *
 * <p>Registering no secret couples the class to BOTH phase-3 edits. If the shipped default
 * regressed to a dual allowlist, {@code loadHmacKey} would demand the now-absent secret and the
 * context would fail to start, naming {@code JWT_ACCEPTED_ALGS}. If a shipped {@code secret:} were
 * resurrected, this would silently stop exercising the deployed shape - which no assertion in the
 * fleet template can see, so {@link #shippedConfigBindsNoLegacySecret()} below closes that half
 * explicitly.
 *
 * <p>Two registrations exist purely to let the context boot without the overlay, and are noted
 * because they are payment-specific: {@code security.webhook.secret} (shipped as {@code
 * ${PAYMENT_WEBHOOK_SECRET}} with no default, unlike product's internal-api-key) and {@code
 * app.scheduling.enabled=false} (payment's {@code SchedulingConfig} is {@code matchIfMissing =
 * true}, so without it the outbox relay auto-fires every second against the compose hostname {@code
 * rabbitmq}). Neither touches the JWT posture under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Rs256OnlyNoSecretValidationIntegrationTest {

  private static final String BASE = "/api/v1/payments";
  private static final long OWNER_ID = 7L;
  private static final String OWNER_SUBJECT = "7";

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payment_db")
          .withUsername("payment")
          .withPassword("payment");

  @BeforeAll
  static void startContainer() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
  }

  /**
   * Only what the context cannot boot without. {@code accepted-algs} is NOT registered - it is the
   * property under test and must come from the shipped file - and neither is {@code
   * security.jwt.secret}.
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // Same-kid override of the shipped `user-rs256-2026-07: ${JWT_PUBLIC_KEY_PATH}` entry; see
    // AbstractIntegrationTest#jwtKeyProperties for why the kid must not drift.
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    // Boot-only, payment-specific (see class javadoc). Not part of the posture under test.
    registry.add("security.webhook.secret", () -> "test-webhook-secret");
    registry.add("app.scheduling.enabled", () -> "false");
    // Intentionally NOT registering security.jwt.accepted-algs or security.jwt.secret: the shipped
    // default and the absent secret ARE what this class exists to pin.
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private JwtProperties jwtProperties;

  // No broker, no outbound HTTP — mocked to boot the context, neither is invoked by these paths.
  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  @Test
  void rs256_accepted_whenSecretAbsent() throws Exception {
    UUID paymentId = seedPayment(OWNER_ID);
    String token = JwtTestKeys.mintRs256(OWNER_SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);

    mockMvc
        .perform(get(BASE + "/" + paymentId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(paymentId.toString())));
  }

  @Test
  void freshHs256_rejected401_whenSecretAbsent() throws Exception {
    String path = BASE + "/" + UUID.randomUUID();

    MvcResult result =
        mockMvc
            .perform(
                get(path).header("Authorization", "Bearer " + JwtTestKeys.mintHs256(OWNER_SUBJECT)))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Authentication required")))
            .andExpect(jsonPath("$.path", is(path)))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>(body.propertyNames());
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "401 envelope must expose exactly the four contract keys");
  }

  /**
   * Closes the half the fleet template cannot see. The two rows above stay green if a shipped
   * {@code security.jwt.secret:} is resurrected - an inert secret changes nothing while HS256 is
   * off the allowlist - so the class would quietly stop exercising the deployed shape with no red
   * anywhere. Binding the property directly makes that regression fail by name. product and cart
   * carry the same blind spot; this row is the payment-side fix for it.
   */
  @Test
  void shippedConfigBindsNoLegacySecret() {
    // assertAll, not two bare statements: the halves are independent regressions and a simultaneous
    // double regression must report BOTH. A bare assertNull would dominate the allowlist assertion
    // and make it unfalsifiable by position (contract 4h(2)) - the exact defect this wave exists to
    // remove, so it must not be reintroduced on the money service's security-posture pin.
    assertAll(
        () ->
            assertNull(
                jwtProperties.secret(),
                "the shipped application.yml must bind no legacy HS256 secret (phase-3 posture, D3)"),
        () ->
            assertEquals(
                List.of("RS256"),
                jwtProperties.acceptedAlgs(),
                "the shipped JWT_ACCEPTED_ALGS default must stay RS256-only"));
  }

  private UUID seedPayment(long userId) {
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            userId,
            new BigDecimal("39.98"),
            "EUR",
            PaymentStatus.SUCCEEDED,
            "sandbox",
            "pm_seed",
            "key-" + UUID.randomUUID());
    return paymentRepository.saveAndFlush(payment).getId();
  }
}
