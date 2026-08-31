package com.ecommerce.user;

import static com.ecommerce.user.support.Iso8601.assertUtcInstant;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the wire FORMAT of every date this service emits over REST — {@code created_at}, {@code
 * updated_at} and the envelope {@code timestamp} — as an ISO-8601 UTC <em>string</em>.
 *
 * <p>The rest of the suite only ever asserts these fields are non-null, which a serializer change
 * flipping them to epoch numbers would satisfy: {@code notNullValue()} passes just as happily on
 * {@code 1785832017.565}. Both rendering paths are covered, because they are configured
 * independently and can drift apart: path A is the hand-written {@code objectMapper.writeValue} in
 * {@code RestAuthenticationEntryPoint}, path B is the message-converter stack behind {@code
 * GlobalExceptionHandler} and the controllers.
 *
 * <p>The error rows also assert the envelope key SET, not a subset — {@code ApiError} carries a
 * fifth component ({@code fields}) suppressed by {@code @JsonInclude(NON_NULL)}, so a change in
 * null handling would ADD {@code fields: null} to bodies that must stay at four keys.
 */
@AutoConfigureMockMvc
class RestDateFormatIntegrationTest extends AbstractIntegrationTest {

  private static final String PROFILE_PATH = "/api/v1/users/me";
  private static final String REGISTER_PATH = "/api/v1/auth/register";
  private static final String VALID_PASSWORD = "Sup3rSecret12";
  private static final Set<String> FOUR_KEY_ENVELOPE =
      Set.of("error", "message", "timestamp", "path");

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
  void register201_createdAt_isIso8601UtcString() throws Exception {
    JsonNode body =
        bodyOf(
            mockMvc
                .perform(
                    post(REGISTER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("date-register@example.com", "Dana")))
                .andExpect(status().isCreated()));

    assertUtcInstant(body, "created_at");
  }

  @Test
  void profile200_createdAtAndUpdatedAt_areIso8601UtcStrings() throws Exception {
    register("date-profile@example.com", "Pina");
    String token = login("date-profile@example.com");

    JsonNode body =
        bodyOf(
            mockMvc
                .perform(get(PROFILE_PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));

    assertUtcInstant(body, "created_at");
    assertUtcInstant(body, "updated_at");
  }

  @Test
  void profileUpdate200_createdAtAndUpdatedAt_areIso8601UtcStrings() throws Exception {
    register("date-update@example.com", "Udo");
    String token = login("date-update@example.com");

    JsonNode body =
        bodyOf(
            mockMvc
                .perform(
                    put(PROFILE_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Udo Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Udo Renamed"))));

    assertUtcInstant(body, "created_at");
    assertUtcInstant(body, "updated_at");
  }

  /** Path A: the entry point serialises the envelope itself, bypassing the converter stack. */
  @Test
  void unauthorized401_timestamp_isIso8601UtcString_andEnvelopeStaysFourKeys() throws Exception {
    JsonNode body = bodyOf(mockMvc.perform(get(PROFILE_PATH)).andExpect(status().isUnauthorized()));

    assertUtcInstant(body, "timestamp");
    assertKeys(body, FOUR_KEY_ENVELOPE);
  }

  /** Path B, domain error: {@code ApiError.of} leaves {@code fields} null — it must stay absent. */
  @Test
  void conflict409_timestamp_isIso8601UtcString_andEnvelopeStaysFourKeys() throws Exception {
    register("date-conflict@example.com", "Cora");

    JsonNode body =
        bodyOf(
            mockMvc
                .perform(
                    post(REGISTER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("date-conflict@example.com", "Cora Again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("EMAIL_ALREADY_REGISTERED"))));

    assertUtcInstant(body, "timestamp");
    assertKeys(body, FOUR_KEY_ENVELOPE);
  }

  /** Path B, framework error: rendered from {@code handleExceptionInternal}, not a controller. */
  @Test
  void notFound404_timestamp_isIso8601UtcString_andEnvelopeStaysFourKeys() throws Exception {
    register("date-404@example.com", "Nora");
    String token = login("date-404@example.com");

    JsonNode body =
        bodyOf(
            mockMvc
                .perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND"))));

    assertUtcInstant(body, "timestamp");
    assertKeys(body, FOUR_KEY_ENVELOPE);
  }

  /**
   * The one body where {@code fields} is populated. Pinning it as a five-key set is the positive
   * control for the four-key rows above: it proves those stay at four because the component is
   * null, not because the serializer has stopped emitting {@code fields} altogether.
   */
  @Test
  void validationError400_timestamp_isIso8601UtcString_andEnvelopeCarriesFields() throws Exception {
    JsonNode body =
        bodyOf(
            mockMvc
                .perform(
                    post(REGISTER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("not-an-email", "Val")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR"))));

    assertUtcInstant(body, "timestamp");
    assertKeys(body, Set.of("error", "message", "timestamp", "path", "fields"));
  }

  private static void assertKeys(JsonNode body, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    body.propertyNames().forEach(actual::add);
    assertEquals(expected, actual, "envelope must expose exactly the contract keys");
  }

  private JsonNode bodyOf(ResultActions actions) throws Exception {
    MvcResult result = actions.andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private void register(String email, String name) throws Exception {
    mockMvc
        .perform(
            post(REGISTER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email, name)))
        .andExpect(status().isCreated());
  }

  private String login(String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"" + email + "\",\"password\":\"" + VALID_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("access_token")
        .asText();
  }

  private String registerBody(String email, String name) {
    return "{\"email\":\""
        + email
        + "\",\"password\":\""
        + VALID_PASSWORD
        + "\",\"name\":\""
        + name
        + "\"}";
  }
}
