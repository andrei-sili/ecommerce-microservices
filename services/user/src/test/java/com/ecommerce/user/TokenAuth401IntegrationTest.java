package com.ecommerce.user;

import static com.ecommerce.user.support.ErrorEnvelopes.assertJsonNotProblem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pins the HTTP-level {@code 401} contract of the JWT security filter chain on {@code GET
 * /api/v1/users/me}: a missing header, every non-{@code Bearer} auth scheme, a malformed token, a
 * tampered signature, and an expired token are all rejected with the standard four-field error
 * envelope — with the exact same key-set, so the cause never leaks. Exercises the REAL {@code
 * FilterChainProxy} (no mocked security beans, no auth request post-processors) so a regression in
 * scheme parsing, expiry enforcement, or envelope rendering turns this suite red.
 */
@AutoConfigureMockMvc
class TokenAuth401IntegrationTest extends AbstractIntegrationTest {

  private static final String PROFILE_PATH = "/api/v1/users/me";
  private static final String VALID_PASSWORD = "Sup3rSecret12";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @BeforeEach
  void cleanDatabase() {
    refreshTokenRepository.deleteAll();
    outboxEventRepository.deleteAll();
    userRepository.deleteAll();
  }

  /**
   * Anti-vacuous control: a token minted through the exact test path (context {@link
   * JwtProperties}) for a real user id is accepted. Without this, the 401s below could be a broken
   * mint (a wrong secret would 401 everything and look like a passing contract).
   */
  @Test
  void validMintedToken_isAccepted_provingMintPath() throws Exception {
    long userId = registerUser("control@example.com", "Control");

    mockMvc
        .perform(get(PROFILE_PATH).header("Authorization", "Bearer " + mintValidToken(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email", is("control@example.com")));
  }

  @Test
  void expiredToken_returns401WithStandardEnvelope() throws Exception {
    long userId = registerUser("expired@example.com", "Expired");

    expectUnauthorizedEnvelope(
        mockMvc.perform(
            get(PROFILE_PATH).header("Authorization", "Bearer " + mintExpiredToken(userId))));
  }

  /**
   * Every case carries a fully VALID token — only the scheme is wrong — so this pins scheme
   * matching, not token validity. {@code bearer} (lowercase) and {@code Bearer}-glued are
   * deliberate: the contract mandates the strict, case-sensitive {@code "Bearer "} prefix.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "Basic scheme,Basic dXNlcjpwYXNz",
    "Token scheme,Token {token}",
    "lowercase bearer scheme,bearer {token}",
    "Bearer glued to token,Bearer{token}",
    "raw token without scheme,{token}"
  })
  void nonBearerScheme_returns401WithStandardEnvelope(String label, String headerTemplate)
      throws Exception {
    long userId = registerUser("scheme@example.com", "Scheme");
    String headerValue = headerTemplate.replace("{token}", mintValidToken(userId));

    expectUnauthorizedEnvelope(
        mockMvc.perform(get(PROFILE_PATH).header("Authorization", headerValue)));
  }

  /**
   * No {@code Authorization} header at all — the filter never authenticates, entry point renders.
   */
  @Test
  void missingAuthorizationHeader_returns401WithStandardEnvelope() throws Exception {
    expectUnauthorizedEnvelope(mockMvc.perform(get(PROFILE_PATH)));
  }

  /** Correct {@code Bearer} scheme but a token that is not a parseable JWT. */
  @Test
  void malformedBearerToken_returns401WithStandardEnvelope() throws Exception {
    expectUnauthorizedEnvelope(
        mockMvc.perform(get(PROFILE_PATH).header("Authorization", "Bearer not.a.jwt")));
  }

  /**
   * A structurally valid, correctly-signed token whose signature is then corrupted — proves expiry
   * is not the only validation the filter enforces (signature verification also gates access).
   */
  @Test
  void bearerTokenWithTamperedSignature_returns401WithStandardEnvelope() throws Exception {
    long userId = registerUser("tampered@example.com", "Tampered");
    String tampered = tamperSignature(mintValidToken(userId));

    expectUnauthorizedEnvelope(
        mockMvc.perform(get(PROFILE_PATH).header("Authorization", "Bearer " + tampered)));
  }

  private void expectUnauthorizedEnvelope(ResultActions actions) throws Exception {
    MvcResult result = actions.andExpect(status().isUnauthorized()).andReturn();

    // Content-Type before the body: a media-type drift must be reported as a media-type drift, not
    // as "No value at JSON path" from a body matcher that ran first against an unreadable body.
    assertJsonNotProblem(result);
    jsonPath("$.error", is("UNAUTHORIZED")).match(result);
    jsonPath("$.message", is("Authentication required")).match(result);
    jsonPath("$.path", is(PROFILE_PATH)).match(result);
    jsonPath("$.timestamp", notNullValue()).match(result);

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    // timestamp must be a parseable ISO-8601 instant (throws otherwise).
    Instant.parse(body.get("timestamp").asText());
    // Exact key-set: the four causes (missing / non-Bearer / malformed / expired) must be
    // indistinguishable — no extension field may leak which one occurred.
    Set<String> keys = new HashSet<>();
    body.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "401 envelope must expose exactly the four contract keys");
  }

  private String mintValidToken(long userId) {
    return new JwtService(jwtProperties, meterRegistry).issueAccessToken(userId, Set.of("USER"));
  }

  private String mintExpiredToken(long userId) {
    // Negative TTL puts exp 120s in the past — well beyond clock-granularity ambiguity.
    JwtProperties expiredProps =
        new JwtProperties(
            jwtProperties.secret(),
            -120,
            jwtProperties.refreshTokenTtlSeconds(),
            jwtProperties.issuer(),
            jwtProperties.signingAlg(),
            jwtProperties.acceptedAlgs(),
            jwtProperties.privateKeyPath(),
            jwtProperties.publicKeys());
    return new JwtService(expiredProps, meterRegistry).issueAccessToken(userId, Set.of("USER"));
  }

  private static String tamperSignature(String token) {
    // Flip one char in the MIDDLE of the signature segment. A mid-segment base64url char carries
    // all
    // six significant bits, so the decoded signature bytes are guaranteed to change → HMAC
    // verification fails deterministically. (The trailing char's low bits are "don't care", so a
    // naive last-char flip could decode to the same bytes and still verify — hence the middle.)
    int sigStart = token.lastIndexOf('.') + 1;
    int idx = (sigStart + token.length()) / 2;
    char flipped = token.charAt(idx) == 'A' ? 'B' : 'A';
    return token.substring(0, idx) + flipped + token.substring(idx + 1);
  }

  private long registerUser(String email, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"password\":\""
                            + VALID_PASSWORD
                            + "\",\"name\":\""
                            + name
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }
}
