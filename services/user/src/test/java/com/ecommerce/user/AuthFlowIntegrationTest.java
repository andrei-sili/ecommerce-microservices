package com.ecommerce.user;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private static final String VALID_PASSWORD = "Sup3rSecret12";

  @BeforeEach
  void cleanDatabase() {
    refreshTokenRepository.deleteAll();
    outboxEventRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void register_returns201_withoutPasswordAndWritesOutbox() throws Exception {
    long outboxBefore = outboxEventRepository.count();

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("alice@example.com", VALID_PASSWORD, "Alice")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id", notNullValue()))
        .andExpect(jsonPath("$.email", is("alice@example.com")))
        .andExpect(jsonPath("$.name", is("Alice")))
        .andExpect(jsonPath("$.created_at", notNullValue()))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.password_hash").doesNotExist());

    org.junit.jupiter.api.Assertions.assertEquals(
        outboxBefore + 1, outboxEventRepository.count(), "UserRegistered must be recorded");
  }

  @Test
  void register_normalizesEmailLowercase_and409OnDuplicate() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("Bob@Example.com", VALID_PASSWORD, "Bob")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email", is("bob@example.com")));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("bob@example.com", VALID_PASSWORD, "Bobby")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", is("EMAIL_ALREADY_REGISTERED")))
        .andExpect(jsonPath("$.timestamp", notNullValue()))
        .andExpect(jsonPath("$.path", is("/api/v1/auth/register")));
  }

  @Test
  void register_rejectsWeakPassword_with400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("weak@example.com", "short", "Weak")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")));
  }

  @Test
  void login_returnsTokens_andRejectsWrongPasswordGenerically() throws Exception {
    register("carol@example.com", VALID_PASSWORD, "Carol");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("carol@example.com", VALID_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.access_token", notNullValue()))
        .andExpect(jsonPath("$.refresh_token", notNullValue()))
        .andExpect(jsonPath("$.token_type", is("Bearer")))
        .andExpect(jsonPath("$.expires_in", is(900)));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("carol@example.com", "WrongPassword12")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")));
  }

  @Test
  void login_unknownEmail_returnsSameGenericError() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("nobody@example.com", VALID_PASSWORD)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")));
  }

  @Test
  void refresh_rotatesToken_andOldOneIsRejected() throws Exception {
    register("dave@example.com", VALID_PASSWORD, "Dave");
    JsonNode tokens = login("dave@example.com", VALID_PASSWORD);
    String oldRefresh = tokens.get("refresh_token").asText();

    MvcResult rotated =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + oldRefresh + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token", notNullValue()))
            .andExpect(jsonPath("$.refresh_token", notNullValue()))
            .andReturn();

    String newRefresh =
        objectMapper
            .readTree(rotated.getResponse().getContentAsString())
            .get("refresh_token")
            .asText();
    org.junit.jupiter.api.Assertions.assertNotEquals(oldRefresh, newRefresh);

    // Old (rotated) token must now be rejected.
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + oldRefresh + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("INVALID_REFRESH_TOKEN")));
  }

  @Test
  void me_requiresToken_and401WithoutOne() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void me_returnsProfile_withValidToken() throws Exception {
    register("erin@example.com", VALID_PASSWORD, "Erin");
    String access = login("erin@example.com", VALID_PASSWORD).get("access_token").asText();

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email", is("erin@example.com")))
        .andExpect(jsonPath("$.roles[0]", is("USER")))
        .andExpect(jsonPath("$.password_hash").doesNotExist());
  }

  @Test
  void updateName_persists() throws Exception {
    register("frank@example.com", VALID_PASSWORD, "Frank");
    String access = login("frank@example.com", VALID_PASSWORD).get("access_token").asText();

    mockMvc
        .perform(
            put("/api/v1/users/me")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Franklin\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name", is("Franklin")));
  }

  @Test
  void changePassword_returns204_andRevokesRefreshTokens() throws Exception {
    register("grace@example.com", VALID_PASSWORD, "Grace");
    JsonNode tokens = login("grace@example.com", VALID_PASSWORD);
    String access = tokens.get("access_token").asText();
    String refresh = tokens.get("refresh_token").asText();

    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"current_password\":\""
                        + VALID_PASSWORD
                        + "\",\"new_password\":\"BrandN3wPass99\"}"))
        .andExpect(status().isNoContent());

    // Existing refresh token must be revoked after a password change.
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized());

    // New password works.
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("grace@example.com", "BrandN3wPass99")))
        .andExpect(status().isOk());
  }

  @Test
  void changePassword_wrongCurrent_returns401() throws Exception {
    register("heidi@example.com", VALID_PASSWORD, "Heidi");
    String access = login("heidi@example.com", VALID_PASSWORD).get("access_token").asText();

    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"current_password\":\"WrongCurrent12\",\"new_password\":\"BrandN3wPass99\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")));
  }

  @Test
  void protectedEndpoint_withTamperedToken_returns401() throws Exception {
    register("ivan@example.com", VALID_PASSWORD, "Ivan");
    String access = login("ivan@example.com", VALID_PASSWORD).get("access_token").asText();

    // Flip a char in the MIDDLE of the signature segment, not the last base64url chars: the
    // trailing
    // chars carry don't-care bits, so a tail edit can decode to the SAME signature bytes and the
    // token still validates (200, flaky). A middle char is a full 6-bit group, so the decoded
    // signature always changes. Assert the tampered token differs before sending, so a no-op tamper
    // fails loudly instead of masquerading as a passing 401.
    int lastDot = access.lastIndexOf('.');
    String signature = access.substring(lastDot + 1);
    int mid = signature.length() / 2;
    char flipped = signature.charAt(mid) == 'A' ? 'B' : 'A';
    String tampered =
        access.substring(0, lastDot + 1)
            + signature.substring(0, mid)
            + flipped
            + signature.substring(mid + 1);
    assertNotEquals(access, tampered, "tampered token must differ from the original");

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tampered))
        .andExpect(status().isUnauthorized());
  }

  private void register(String email, String password, String name) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email, password, name)))
        .andExpect(status().isCreated());
  }

  private JsonNode login(String email, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(email, password)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private String registerBody(String email, String password, String name) {
    return "{\"email\":\""
        + email
        + "\",\"password\":\""
        + password
        + "\",\"name\":\""
        + name
        + "\"}";
  }

  private String loginBody(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
  }
}
