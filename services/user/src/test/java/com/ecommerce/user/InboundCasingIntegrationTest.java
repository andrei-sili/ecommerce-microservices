package com.ecommerce.user;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.support.ErrorEnvelopes;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract B13: the INBOUND half of the snake_case wire contract, on the only two request bodies in
 * this service that can prove anything about it.
 *
 * <p><strong>Why only two.</strong> {@code spring.jackson.property-naming-strategy: SNAKE_CASE}
 * maps a Java property to a wire key by inserting underscores at word boundaries, so a single-token
 * property is its own snake_case form and binds identically under every naming strategy. Register
 * ({@code email}, {@code password}, {@code name}) and login ({@code email}, {@code password}) are
 * therefore green whether the strategy binds or not — they prove nothing, and their existing
 * coverage must not be mistaken for coverage of this row. {@code refresh_token} and {@code
 * current_password} / {@code new_password} are this service's ONLY multi-word inbound fields.
 *
 * <p><strong>Why the negative rows carry the weight.</strong> The positive rows fail if the
 * strategy stops applying; but a strategy that became LENIENT — accepting both casings — would
 * leave them green while silently widening the accepted wire format. Only a camelCase body that
 * must be REJECTED can see that. The inbound half has to fail loudly, never bind by accident.
 *
 * <p>The 400 rows assert the field name Bean Validation reports, which is the JAVA property ({@code
 * refreshToken}), not the wire key: with the camelCase key ignored the record component stays null
 * and {@code @NotBlank} fires against the property. That is the observable difference between "was
 * not bound" and "was bound from the wrong key".
 */
@AutoConfigureMockMvc
class InboundCasingIntegrationTest extends AbstractIntegrationTest {

  private static final String VALID_PASSWORD = "Sup3rSecret12";
  private static final String NEW_PASSWORD = "BrandN3wPass99";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @BeforeEach
  void cleanDatabase() {
    refreshTokenRepository.deleteAll();
    outboxEventRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void refresh_withSnakeCaseKey_returns200_andExactlyTheFourTokenKeys() throws Exception {
    String refresh = login(register("kim@example.com")).get("refresh_token").asString();

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refresh_token\":\"" + refresh + "\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(ErrorEnvelopes.JSON))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

    Set<String> keys = new HashSet<>();
    body.propertyNames().forEach(keys::add);
    assertEquals(
        Set.of("access_token", "refresh_token", "token_type", "expires_in"),
        keys,
        "the refresh response must expose exactly the four contract keys, all snake_case");

    assertEquals("Bearer", body.get("token_type").asString());
    assertEquals(900, body.get("expires_in").asInt());
    assertTrue(
        body.get("access_token").isString() && !body.get("access_token").asString().isBlank());
  }

  @Test
  void refresh_withCamelCaseKey_returns400_validationError_namingTheJavaProperty()
      throws Exception {
    String refresh = login(register("liam@example.com")).get("refresh_token").asString();

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refresh + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(ErrorEnvelopes.JSON))
            .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
            .andExpect(jsonPath("$.message", is("Request validation failed")))
            .andReturn();

    assertNamesField(result, "refreshToken");
  }

  @Test
  void changePassword_withSnakeCaseKeys_returns204() throws Exception {
    String access = login(register("mia@example.com")).get("access_token").asString();

    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"current_password\":\""
                        + VALID_PASSWORD
                        + "\",\"new_password\":\""
                        + NEW_PASSWORD
                        + "\"}"))
        .andExpect(status().isNoContent());
  }

  /**
   * The mirror negative, and the one with real consequence: if this ever bound, a caller could
   * change a password through a wire format the contract does not define, on the service that holds
   * the bcrypt hashes.
   */
  @Test
  void changePassword_withCamelCaseKeys_returns400_validationError_namingBothJavaProperties()
      throws Exception {
    String access = login(register("noah@example.com")).get("access_token").asString();

    MvcResult result =
        mockMvc
            .perform(
                put("/api/v1/users/me/password")
                    .header("Authorization", "Bearer " + access)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"currentPassword\":\""
                            + VALID_PASSWORD
                            + "\",\"newPassword\":\""
                            + NEW_PASSWORD
                            + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(ErrorEnvelopes.JSON))
            .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
            .andReturn();

    assertNamesField(result, "currentPassword");
    assertNamesField(result, "newPassword");

    // The rejection must be a rejection, not a partial application: the old password still works.
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("noah@example.com", VALID_PASSWORD)))
        .andExpect(status().isOk());
  }

  private void assertNamesField(MvcResult result, String expected) throws Exception {
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    Set<String> named = new HashSet<>();
    for (JsonNode violation : body.get("fields")) {
      named.add(violation.get("field").asString());
    }
    assertTrue(
        named.contains(expected),
        "the validation envelope must name the unbound Java property '"
            + expected
            + "', was: "
            + named);
  }

  private String register(String email) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + email
                        + "\",\"password\":\""
                        + VALID_PASSWORD
                        + "\",\"name\":\"Casing\"}"))
        .andExpect(status().isCreated());
    return email;
  }

  private JsonNode login(String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private String loginBody(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
  }
}
