package com.ecommerce.cart;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.support.JwtTestKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase-3 production shape: {@code accepted-algs} rides the shipped yaml default (RS256) and the
 * {@code security.jwt.secret} property is ENTIRELY ABSENT — the exact posture the deploy ships.
 *
 * <p>Every other dual-accept suite injects a test secret via {@link
 * com.ecommerce.cart.support.AbstractIntegrationTest}, so none of them proves the secret can be
 * missing. This standalone boot deliberately registers no secret ({@code JwtProperties.secret}
 * binds {@code null}) and no {@code accepted-algs} override, coupling the test to BOTH phase-3
 * edits: if the yaml default regressed to a dual allowlist, {@code loadHmacKey} would demand the
 * now-absent secret and the context would fail to start; if the secret binding were resurrected,
 * this would no longer exercise the deployed shape. A valid RS256 token is still accepted and a
 * fresh HS256 forgery is rejected with the pinned 401 envelope (HS256 is off at the allowlist, so
 * the missing secret is irrelevant to the rejection).
 */
@SpringBootTest
@AutoConfigureMockMvc
class Rs256OnlySecretAbsentValidationIntegrationTest {

  private static final long USER_ID = 7L;
  private static final String CART_PATH = "/api/v1/cart";

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("cart_db")
          .withUsername("cart")
          .withPassword("cart");

  @BeforeAll
  static void startContainer() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // Wire only the RS256 public key. accepted-algs is left to the yaml default (RS256) and the
    // secret is deliberately never registered — this is the whole point of the test.
    registry.add(
        "security.jwt.public-keys." + JwtTestKeys.KID_A, () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
  }

  @Autowired private MockMvc mockMvc;

  @Test
  void rs256Token_accepted_whenSecretPropertyAbsent() throws Exception {
    String token = JwtTestKeys.mintRs256(USER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);
    mockMvc
        .perform(get(CART_PATH).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id", is((int) USER_ID)));
  }

  @Test
  void freshHs256Token_rejected401_whenSecretPropertyAbsent() throws Exception {
    String token = JwtTestKeys.mintHs256(USER_ID);
    mockMvc
        .perform(get(CART_PATH).header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.message", is("Authentication required")))
        .andExpect(jsonPath("$.path", is(CART_PATH)))
        .andExpect(jsonPath("$.timestamp", notNullValue()));
  }
}
