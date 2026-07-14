package com.ecommerce.user;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.model.RefreshToken;
import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.security.TokenHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Refresh continuity across the signer flip. Refresh tokens are opaque SHA-256 DB rows, independent
 * of the access-token algorithm, so a session opened PRE-flip (HS256 era) must keep working POST-
 * flip and yield an RS256 access token — no session invalidation. Here the context runs with {@code
 * signing-alg=RS256}; a refresh row is seeded directly (representing that pre-flip session — its
 * creation never touched the alg) and refreshed through the REAL {@code POST /api/v1/auth/refresh}
 * path, which must return an RS256 access token and rotate the refresh token.
 */
@AutoConfigureMockMvc
class RefreshContinuityIntegrationTest extends AbstractIntegrationTest {

  private static final String SIGNING_KID = "user-rs256-2026-07";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private TokenHasher tokenHasher;
  @Autowired private PasswordEncoder passwordEncoder;

  @DynamicPropertySource
  static void flipSignerToRs256(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.signing-alg", () -> "RS256");
  }

  @BeforeEach
  void cleanDatabase() {
    refreshTokenRepository.deleteAll();
    outboxEventRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void preFlipRefreshToken_refreshesToRs256AccessToken_withoutSessionLoss() throws Exception {
    User user =
        userRepository.save(
            new User(
                "session@example.com", passwordEncoder.encode("Sup3rSecret12"), "Session", "USER"));

    // A pre-flip session: opaque refresh row, created independently of the (then HS256) access alg.
    String preFlipRefresh = tokenHasher.generateRawToken();
    refreshTokenRepository.save(
        new RefreshToken(
            user.getId(), tokenHasher.hash(preFlipRefresh), Instant.now().plusSeconds(604800)));

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + preFlipRefresh + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token", notNullValue()))
            .andExpect(jsonPath("$.refresh_token", notNullValue()))
            .andExpect(jsonPath("$.token_type", is("Bearer")))
            .andExpect(jsonPath("$.expires_in", is(900)))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

    // The fresh access token is RS256 with the pinned kid — the flip took effect on refresh.
    JsonNode header = decodeHeader(body.get("access_token").asText());
    assertEquals("RS256", header.get("alg").asText());
    assertEquals(SIGNING_KID, header.get("kid").asText());

    // Rotation still happens: the pre-flip refresh token is replaced (no session loss, but
    // rotated).
    assertNotEquals(preFlipRefresh, body.get("refresh_token").asText());
  }

  private JsonNode decodeHeader(String token) throws Exception {
    String headerJson =
        new String(
            Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
            StandardCharsets.UTF_8);
    return objectMapper.readTree(headerJson);
  }
}
