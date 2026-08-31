package com.ecommerce.user;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.model.RefreshToken;
import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.security.TokenHasher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
  @Autowired private JwtProperties jwtProperties;

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
    assertNotNull(header.get("alg"), "refreshed access token header must carry an alg key");
    assertEquals(
        "RS256", header.get("alg").asText(), "refreshed access token must carry alg=RS256");
    assertNotNull(header.get("kid"), "refreshed access token header must carry a kid key");
    assertEquals(
        SIGNING_KID,
        header.get("kid").asText(),
        "refreshed access token must carry the pinned kid");

    // Rotation still happens: the pre-flip refresh token is replaced (no session loss, but
    // rotated).
    assertNotEquals(preFlipRefresh, body.get("refresh_token").asText());
  }

  /**
   * Pins the shipped REFRESH-token lifetime, which nothing else in the suite binds.
   *
   * <p>Asymmetry worth stating rather than hiding: the access-token TTL is pinned
   * <em>behaviourally</em> in three places ({@code $.expires_in} is 900 above, and in {@code
   * AuthFlowIntegrationTest} and {@code Rs256SigningIntegrationTest}), because a login response
   * carries it. A refresh token is an opaque SHA-256 row whose lifetime appears in no response
   * body, so the only available pin is the bound property. That makes this a weaker pin than its
   * access-token counterpart — it proves the shipped default still binds, not that the persisted
   * row honours it — and it exists so a silent change to the shipped session lifetime cannot pass
   * green. Before the S-shadow slice neither TTL was pinned against the shipped file at all: the
   * test yml re-declared both, so the suite asserted values it had handed itself.
   */
  @Test
  void shippedRefreshTokenTtl_isBoundFromTheShippedFile() {
    assertEquals(
        604800,
        jwtProperties.refreshTokenTtlSeconds(),
        "security.jwt.refresh-token-ttl-seconds must still bind from the shipped application.yml"
            + " (${JWT_REFRESH_TTL_SECONDS:604800}) — it is the session lifetime");
  }

  private JsonNode decodeHeader(String token) throws Exception {
    String headerJson =
        new String(
            Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
            StandardCharsets.UTF_8);
    return objectMapper.readTree(headerJson);
  }
}
