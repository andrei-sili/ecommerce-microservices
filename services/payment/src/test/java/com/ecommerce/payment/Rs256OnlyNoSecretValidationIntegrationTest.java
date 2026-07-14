package com.ecommerce.payment;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.support.JwtTestKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase-3 production shape: the context boots with {@code accepted-algs=RS256} AND NO {@code
 * security.jwt.secret} property at all — exactly what ships once application.yml's {@code secret:}
 * line is deleted (JwtProperties.secret binds null, fail-closed per D3). No other suite proves
 * this: the dual-accept base and {@code Rs256OnlyValidationIntegrationTest} still wire a legacy
 * secret via {@code @DynamicPropertySource}, so they never exercise startup and validation WITHOUT
 * one — this deliberately does not extend that base for exactly that reason. A validator never
 * signs, so {@code loadHmacKey} short-circuits to null when HS256 is off; this pins that the absent
 * secret breaks neither startup nor the RS256 path, and that a fresh legacy HS256 forgery is still
 * rejected 401.
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
   * The exact prod property shape: RS256-only allowlist and the RS256 public key, but deliberately
   * NO {@code security.jwt.secret} — the absence phase 3 ships. The RabbitMQ/scheduling defaults
   * come from {@code src/test/resources/application.yml}.
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("security.jwt.accepted-algs", () -> "RS256");
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    // Intentionally NOT registering security.jwt.secret: this IS the property under test.
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PaymentRepository paymentRepository;

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
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Authentication required")))
            .andExpect(jsonPath("$.path", is(path)))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Instant.parse(body.get("timestamp").asText());
    Set<String> keys = new HashSet<>();
    body.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "401 envelope must expose exactly the four contract keys");
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
