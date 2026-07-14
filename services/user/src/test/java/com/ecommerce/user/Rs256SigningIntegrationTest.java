package com.ecommerce.user;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase-2 signer flip through the REAL login seam: with {@code signing-alg=RS256} the token minted
 * by {@code POST /api/v1/auth/login} carries {@code alg=RS256} + the pinned {@code kid}, keeps the
 * 900 s TTL, and — since user configures its own public key under that kid — validates against
 * user's own filter chain (counter + audit reflect the RS256 acceptance). Exercised via the wired
 * {@code JwtService}, not a hand-built one, so a mis-wired signing flag turns this red.
 */
class Rs256SigningIntegrationTest extends AbstractDualAcceptTest {

  private static final String SIGNING_KID = "user-rs256-2026-07";
  private static final String VALID_PASSWORD = "Sup3rSecret12";

  @DynamicPropertySource
  static void flipSignerToRs256(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.signing-alg", () -> "RS256");
  }

  @Test
  void login_issuesRs256TokenWithPinnedKid_andSelfValidates() throws Exception {
    registerUser("signer@example.com", "Signer");
    JsonNode tokens = login("signer@example.com");
    String access = tokens.get("access_token").asText();

    assertEquals(900, tokens.get("expires_in").asInt(), "TTL parity must survive the flip");

    JsonNode header = decodeHeader(access);
    assertEquals("RS256", header.get("alg").asText(), "issued token must carry alg=RS256");
    assertEquals(SIGNING_KID, header.get("kid").asText(), "issued token must carry the pinned kid");

    // Self-issued RS256 token is accepted by user's own dual-accept validator and observed as
    // RS256.
    double before = counterCount("RS256", SIGNING_KID);
    expectProfileOk(access, "signer@example.com");
    assertEquals(before + 1, counterCount("RS256", SIGNING_KID), 0.0001);
    assertAudited("JWT accepted alg=RS256 kid=" + SIGNING_KID);
  }

  private JsonNode login(String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"" + email + "\",\"password\":\"" + VALID_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token", notNullValue()))
            .andExpect(jsonPath("$.token_type", is("Bearer")))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode decodeHeader(String token) throws Exception {
    String headerJson =
        new String(
            Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
            StandardCharsets.UTF_8);
    return objectMapper.readTree(headerJson);
  }
}
