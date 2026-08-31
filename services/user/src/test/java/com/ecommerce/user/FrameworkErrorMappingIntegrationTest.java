package com.ecommerce.user;

import static com.ecommerce.user.support.ErrorEnvelopes.assertJsonNotProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.exception.ApiException;
import com.ecommerce.user.exception.GlobalExceptionHandler;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-context guard for framework/dispatcher error mapping: unmapped path, wrong method, bad media
 * type and unreadable body must render the standard envelope with the correct 4xx status — never a
 * 500 — while security (401) and validation (400) behaviour stay intact. Runs against the real
 * security chain + dispatcher + Postgres, with a token obtained via register -> login.
 */
@AutoConfigureMockMvc
class FrameworkErrorMappingIntegrationTest extends AbstractIntegrationTest {

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

  // 1. Unmapped collection path with a valid token -> 404 RESOURCE_NOT_FOUND, full envelope.
  @Test
  void unmappedPath_returns404_withEnvelope_andJsonContentType_noLeak() throws Exception {
    String token = registerAndLogin("nadia@example.com");

    assertEnvelope(
        mockMvc
            .perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound()),
        jsonPath("$.error", is("RESOURCE_NOT_FOUND")),
        jsonPath("$.message", notNullValue()),
        jsonPath("$.timestamp", notNullValue()),
        jsonPath("$.path", is("/api/v1/users")));
  }

  // 2. Unmapped item path with a valid token -> 404 RESOURCE_NOT_FOUND (message must not echo
  // path).
  @Test
  void unmappedItemPath_returns404_withEnvelope_andNoPathLeak() throws Exception {
    String token = registerAndLogin("oscar@example.com");

    assertEnvelope(
        mockMvc
            .perform(get("/api/v1/users/2").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound()),
        jsonPath("$.error", is("RESOURCE_NOT_FOUND")),
        jsonPath("$.message", is("Resource not found")),
        jsonPath("$.path", is("/api/v1/users/2")));
  }

  // 3. Wrong HTTP method on a mapped route -> 405 + Allow header listing GET and PUT.
  @Test
  void wrongMethod_returns405_withAllowHeader() throws Exception {
    String token = registerAndLogin("paul@example.com");

    assertEnvelope(
        mockMvc
            .perform(post("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isMethodNotAllowed()),
        header().string("Allow", containsString("GET")),
        header().string("Allow", containsString("PUT")),
        jsonPath("$.error", is("METHOD_NOT_ALLOWED")),
        jsonPath("$.path", is("/api/v1/users/me")));
  }

  // 4. Unsupported Content-Type on a body route -> 415 + Accept header present.
  @Test
  void unsupportedMediaType_returns415_withAcceptHeader() throws Exception {
    String token = registerAndLogin("quinn@example.com");

    assertEnvelope(
        mockMvc
            .perform(
                put("/api/v1/users/me")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("just some plain text"))
            .andExpect(status().isUnsupportedMediaType()),
        header().exists("Accept"),
        jsonPath("$.error", is("UNSUPPORTED_MEDIA_TYPE")),
        jsonPath("$.path", is("/api/v1/users/me")));
  }

  // 5. Malformed JSON body (regression guard) -> 400 MALFORMED_REQUEST.
  @Test
  void malformedJson_returns400_malformedRequest() throws Exception {
    String token = registerAndLogin("rita@example.com");

    assertEnvelope(
        mockMvc
            .perform(
                put("/api/v1/users/me")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not json"))
            .andExpect(status().isBadRequest()),
        jsonPath("$.error", is("MALFORMED_REQUEST")),
        jsonPath("$.message", is("Malformed request body")),
        jsonPath("$.path", is("/api/v1/users/me")));
  }

  // 6. Bean-Validation failure (regression guard) -> 400 VALIDATION_ERROR with named fields.
  @Test
  void invalidBody_returns400_validationError_withFields() throws Exception {
    MvcResult result =
        assertEnvelope(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("", "short", "Sam")))
                .andExpect(status().isBadRequest()),
            jsonPath("$.error", is("VALIDATION_ERROR")),
            jsonPath("$.path", is("/api/v1/auth/register")),
            jsonPath("$.fields", notNullValue()),
            jsonPath("$.fields.length()", greaterThan(0)));

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    boolean namesField = false;
    for (JsonNode violation : body.get("fields")) {
      String field = violation.get("field").asText();
      if ("email".equals(field) || "password".equals(field)) {
        namesField = true;
      }
    }
    assertTrue(namesField, "validation envelope must name the offending field (email/password)");
  }

  // 7. Security regression guard: no Authorization header -> 401 UNAUTHORIZED (entry point).
  @Test
  void noToken_returns401_unauthorized() throws Exception {
    assertEnvelope(
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized()),
        jsonPath("$.error", is("UNAUTHORIZED")),
        jsonPath("$.path", is("/api/v1/users")));
  }

  // 8. Happy-path control: a mapped route with a valid token still returns 200.
  @Test
  void mappedRoute_withValidToken_returns200() throws Exception {
    String token = registerAndLogin("tina@example.com");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

    assertJsonNotProblem(result);
    jsonPath("$.email", is("tina@example.com")).match(result);
  }

  /**
   * 9. An unsatisfiable {@code Accept} is a client error, and must never become a 500.
   *
   * <p>The envelope is BUILT for this case ({@code NOT_ACCEPTABLE} / "Requested representation not
   * available") but cannot be WRITTEN — there is no converter that can render {@code ApiError} as
   * XML, which is the whole reason the request was rejected. So what ships is a bare 406 with a
   * zero-length body and no {@code Content-Type}. That gap is pre-existing and out of scope here
   * (contract §8); this row pins the wart so the migration cannot quietly turn it into a 500 or a
   * problem+json document while nobody is looking.
   */
  @Test
  void unacceptableAcceptHeader_returns406_withEmptyBody_notA500() throws Exception {
    String token = registerAndLogin("ursula@example.com");

    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/users/me")
                    .header("Authorization", "Bearer " + token)
                    .accept(MediaType.APPLICATION_XML))
            .andExpect(status().isNotAcceptable())
            .andReturn();

    assertNull(result.getResponse().getContentType(), "406 must carry no Content-Type");
    assertEquals(
        0, result.getResponse().getContentAsByteArray().length, "406 body must stay empty");
  }

  /**
   * 10. {@code path} is echoed straight from {@code request.getRequestURI()}, which is
   * attacker-controlled and still percent-encoded at that point. It must round-trip byte for byte:
   * a decode here would put raw bytes the caller chose into a JSON string, and the response is the
   * one place that value is reflected back. Path B — rendered by the converter stack.
   */
  @Test
  void percentEncodedNonAsciiPath_roundTripsByteIdentically_onPathB() throws Exception {
    String token = registerAndLogin("valerie@example.com");

    assertEnvelope(
        mockMvc
            .perform(
                get(new URI("/api/v1/users/%C3%A9lise")).header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound()),
        jsonPath("$.error", is("RESOURCE_NOT_FOUND")),
        jsonPath("$.path", is("/api/v1/users/%C3%A9lise")));
  }

  /**
   * 11. Same input on path A — the entry point serialises the envelope itself and writes it to
   * {@code getOutputStream()}, so it shares no code with row 10 and can drift independently.
   */
  @Test
  void percentEncodedNonAsciiPath_roundTripsByteIdentically_onPathA() throws Exception {
    assertEnvelope(
        mockMvc
            .perform(get(new URI("/api/v1/users/%C3%A9lise/me")))
            .andExpect(status().isUnauthorized()),
        jsonPath("$.error", is("UNAUTHORIZED")),
        jsonPath("$.path", is("/api/v1/users/%C3%A9lise/me")));
  }

  /**
   * 12. A10, structural half. The catch-all {@code @ExceptionHandler(Exception.class)} must stay
   * the only 500 producer, and no standalone handler may declare a type the {@link
   * ResponseEntityExceptionHandler} base class already maps — that collision is a STARTUP crash
   * ("Ambiguous @ExceptionHandler"), not a test failure, so it takes the service down rather than
   * reddening a row. On the fleet's only JWT signer that is the difference between a bad deploy and
   * a service that never comes up at all.
   *
   * <p>The base-mapped set is read reflectively from the framework on the classpath rather than
   * hard-coded, so this goes red if a future Spring version ADDS a mapping that collides with one
   * of ours — which is the whole reason the row exists during a framework major bump. Both halves
   * of the reflection are asserted non-empty first: an empty set would make {@code
   * doesNotContainAnyElementsOf} trivially true and this row vacuous.
   *
   * <p><b>The standalone set is pinned EXACTLY, not by {@code contains}.</b> A subset assertion
   * discharges the "no ambiguity" half but not the "single 500 producer" half: a brand-new
   * {@code @ExceptionHandler} returning 500 satisfies {@code contains(Exception.class)} while
   * adding the second producer the row forbids. user is the fleet's smallest standalone set — two
   * entries, where product has four — because every framework type is customised through an
   * {@code @Override} of a protected hook instead.
   *
   * <p>Scope, so a green is not read louder than it earns. This pins WHICH types get a standalone
   * handler; it never executes them, so it cannot see {@code handleApi} being changed to render
   * 500. That status is held by the behavioural rows above and in {@code
   * TokenAuth401IntegrationTest}.
   */
  @Test
  void noStandaloneHandlerIsAmbiguousWithTheBaseClass_andTheCatchAllIsTheOnly500() {
    Set<Class<?>> baseMapped = mappedTypes(ResponseEntityExceptionHandler.class);
    Set<Class<?>> standalone = mappedTypes(GlobalExceptionHandler.class);

    assertThat(baseMapped).as("reflection must actually find the base mappings").isNotEmpty();
    assertThat(standalone).as("reflection must actually find our handlers").isNotEmpty();

    assertThat(standalone)
        .as(
            "a standalone @ExceptionHandler declaring a type the base class already maps is an"
                + " ambiguous mapping and fails startup")
        .doesNotContainAnyElementsOf(baseMapped);

    assertThat(standalone)
        .as(
            "the catch-all must remain, and remain one of only two standalone handlers — a new one"
                + " here is a candidate second 500 producer and must be justified, not absorbed")
        .containsExactlyInAnyOrder(ApiException.class, Exception.class);
  }

  private static Set<Class<?>> mappedTypes(Class<?> handler) {
    return Arrays.stream(handler.getDeclaredMethods())
        .map(m -> m.getAnnotation(ExceptionHandler.class))
        .filter(Objects::nonNull)
        .flatMap(a -> Arrays.stream(a.value()))
        .collect(Collectors.toSet());
  }

  /**
   * Runs one error row in the order the contract requires: status (already applied by the caller),
   * then the EXACT {@code Content-Type}, then the body.
   *
   * <p>The order is the point, not a style preference. A media-type drift must be attributed to the
   * media type. With a body matcher running first, the same drift surfaces as {@code No value at
   * JSON path "$.error"} — a real failure naming the wrong cause, which sends the reader hunting a
   * missing field instead of a changed header. Keeping the body matchers as arguments rather than
   * in the {@code andExpect} chain is what makes that order structural instead of a convention the
   * next row can quietly break.
   */
  private MvcResult assertEnvelope(ResultActions actions, ResultMatcher... bodyMatchers)
      throws Exception {
    MvcResult result = actions.andReturn();

    assertJsonNotProblem(result);
    for (ResultMatcher matcher : bodyMatchers) {
      matcher.match(result);
    }
    assertNoLeak(result);
    return result;
  }

  /** No error body may leak the static-resource message, a stack trace or other internals. */
  private void assertNoLeak(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    for (String forbidden :
        new String[] {
          "No static resource",
          "nested exception",
          "Exception",
          "\tat ",
          "java.lang.",
          "org.springframework."
        }) {
      assertFalse(
          body.contains(forbidden), "error body leaked internals (\"" + forbidden + "\"): " + body);
    }
  }

  private String registerAndLogin(String email) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email, VALID_PASSWORD, "Name")))
        .andExpect(status().isCreated());

    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(login.getResponse().getContentAsString())
        .get("access_token")
        .asText();
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
