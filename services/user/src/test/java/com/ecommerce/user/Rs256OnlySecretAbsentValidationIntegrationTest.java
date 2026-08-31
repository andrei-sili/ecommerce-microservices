package com.ecommerce.user;

import static com.ecommerce.user.support.ErrorEnvelopes.assertJsonNotProblem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.support.JwtTestKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase-3 production shape: {@code accepted-algs} rides the shipped yaml default (RS256) and the
 * {@code security.jwt.secret} property is ENTIRELY ABSENT — the exact posture the deploy ships.
 *
 * <p>This class deliberately does NOT extend {@link AbstractIntegrationTest} and carries no
 * {@code @ActiveProfiles("test")}, so it boots {@code src/main/resources/application.yml} ALONE.
 * Every other dual-accept suite here layers the test overlay, which supplies a test secret and
 * widens {@code accepted-algs} to {@code HS256,RS256} — so none of them can prove the secret may be
 * missing, and none would notice the shipped allowlist re-widening. That gap was fleet-wide: {@code
 * product} and {@code cart} have carried this pin since {@code f7e549c}, while {@code user}, {@code
 * order} and {@code payment} could not write it — their test yml shadowed the shipped file
 * outright. Removing that shadow (the S-shadow slice) is what makes this class possible here.
 *
 * <p>Coupled to BOTH phase-3 edits, and each half has its own row so neither claim rests on prose:
 * re-widening the yaml default to a dual allowlist makes {@code loadHmacKey} demand the now-absent
 * secret and the context fails to start; resurrecting a shipped {@code secret:} is caught by {@link
 * #shippedConfig_bindsNoHmacSecret}. That second row is load-bearing — the two request rows below
 * would stay GREEN under a resurrected secret, because HS256 is off at the allowlist and the
 * forgery is therefore rejected for a reason unrelated to the secret. A valid RS256 token is still
 * accepted and a fresh HS256 forgery is rejected with the pinned 401 envelope.
 *
 * <p>Only the shipped file's five no-default placeholders are supplied, via the ENV names the
 * deployment itself uses ({@code JWT_PRIVATE_KEY_PATH} / {@code JWT_PUBLIC_KEY_PATH}) rather than
 * by overriding {@code security.jwt.*} directly — so the shipped key-material wiring shape is
 * exercised instead of bypassed. {@code accepted-algs} and {@code secret} are never registered:
 * that is the whole point, and a future edit that adds either to this class destroys it.
 *
 * <p>{@code admin.bootstrap.*} is pinned empty here for the same reason {@link
 * AbstractIntegrationTest} pins it, and it must stay: this context does not inherit that pin, and
 * OS environment outranks the shipped yaml, so an ambient {@code ADMIN_BOOTSTRAP_EMAIL} / {@code
 * ADMIN_BOOTSTRAP_PASSWORD} would otherwise seed a real ADMIN account during this test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Rs256OnlySecretAbsentValidationIntegrationTest {

  private static final String PROFILE_PATH = "/api/v1/users/me";
  private static final String PASSWORD = "Sup3rSecret12";

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("user_db")
          .withUsername("user_svc")
          .withPassword("test_pw");

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
    registry.add("JWT_PRIVATE_KEY_PATH", () -> JwtTestKeys.PRIVATE_KEY_PATH_A);
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("admin.bootstrap.email", () -> "");
    registry.add("admin.bootstrap.password", () -> "");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtProperties jwtProperties;

  /**
   * Makes the class's own headline claim falsifiable. The two rows below stay green if a {@code
   * secret:} is resurrected in the shipped file — HS256 is off at the allowlist, so the forgery is
   * rejected for a reason that has nothing to do with the secret — which means "the secret property
   * is ENTIRELY ABSENT" would be an unfalsifiable assertion in a docstring. This is the assertion
   * that fails when it stops being true.
   */
  @Test
  void shippedConfig_bindsNoHmacSecret() {
    assertNull(
        jwtProperties.secret(),
        "the shipped application.yml must declare no security.jwt.secret — phase 3 deleted it so"
            + " production is fail-closed on RS256, and a resurrected secret reopens the shared-key"
            + " forgery window");
  }

  @Test
  void rs256Token_accepted_whenSecretPropertyAbsent() throws Exception {
    String email = "rs256-absent-secret@example.com";
    long userId = registerUser(email, "Rs256AbsentSecret");

    mockMvc
        .perform(
            get(PROFILE_PATH)
                .header(
                    "Authorization",
                    "Bearer "
                        + JwtTestKeys.mintRs256(userId, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A)))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        .andExpect(jsonPath("$.email", is(email)));
  }

  @Test
  void freshHs256Token_rejected401_whenSecretPropertyAbsent() throws Exception {
    long userId = registerUser("hs256-absent-secret@example.com", "Hs256AbsentSecret");

    MvcResult result =
        mockMvc
            .perform(
                get(PROFILE_PATH)
                    .header("Authorization", "Bearer " + JwtTestKeys.mintHs256(userId)))
            .andExpect(status().isUnauthorized())
            .andReturn();

    // Content-Type before the body: a media-type drift must be reported as a media-type drift, not
    // as "No value at JSON path" from a body matcher that ran first against an unreadable body.
    assertJsonNotProblem(result);
    jsonPath("$.error", is("UNAUTHORIZED")).match(result);
    jsonPath("$.message", is("Authentication required")).match(result);
    jsonPath("$.path", is(PROFILE_PATH)).match(result);
    jsonPath("$.timestamp", notNullValue()).match(result);
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
                            + PASSWORD
                            + "\",\"name\":\""
                            + name
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }
}
