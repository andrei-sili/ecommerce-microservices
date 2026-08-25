package com.ecommerce.order.security;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.order.support.ContractShape;
import com.ecommerce.order.support.JwtTestKeys;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Slice 5e phase-1 validation matrix under the {@code HS256,RS256} allowlist, through the real
 * Order filter chain against the pinned endpoint {@code GET /api/v1/orders/{id}}. Every happy row
 * asserts the {@code jwt.accepted.tokens} counter tag AND the {@code jwt.audit} line; every abuse
 * row asserts the pinned four-field 401 envelope with observability left flat (indistinguishable
 * causes). The extra ownership row proves the roles/sub claims drive authorization on the new path.
 */
class DualAcceptValidationIntegrationTest extends AbstractDualAcceptTest {

  @Test
  void happyLegacyHs256_returns200_countsAndAudits() throws Exception {
    double before = counterCount("HS256", "-");

    expectOrderOk(JwtTestKeys.mintHs256(OWNER_ID));

    assertEquals(before + 1, counterCount("HS256", "-"), 0.0001);
    assertAudited("JWT accepted alg=HS256 kid=-");
  }

  @Test
  void happyRs256_returns200_countsKid() throws Exception {
    double before = counterCount("RS256", JwtTestKeys.KID_A);

    expectOrderOk(JwtTestKeys.mintRs256(OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));

    assertEquals(before + 1, counterCount("RS256", JwtTestKeys.KID_A), 0.0001);
    assertAudited("JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_A);
  }

  @Test
  void twoKidCoexistence_bothAccepted_withDistinctKidTags() throws Exception {
    double beforeA = counterCount("RS256", JwtTestKeys.KID_A);
    double beforeB = counterCount("RS256", JwtTestKeys.KID_B);

    expectOrderOk(JwtTestKeys.mintRs256(OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
    expectOrderOk(JwtTestKeys.mintRs256(OWNER_ID, JwtTestKeys.KID_B, JwtTestKeys.KEY_PAIR_B));

    assertEquals(beforeA + 1, counterCount("RS256", JwtTestKeys.KID_A), 0.0001);
    assertEquals(beforeB + 1, counterCount("RS256", JwtTestKeys.KID_B), 0.0001);
    assertAudited("JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_A);
    assertAudited("JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_B);
  }

  @Test
  void wrongKeypair_returns401() throws Exception {
    // Valid kid but signed with a different private key → signature verification fails.
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_WRONG));
  }

  @Test
  void expiredRs256_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A, -120));
  }

  @Test
  void tamperedRs256Signature_returns401() throws Exception {
    String tampered =
        JwtTestKeys.tamperSignature(
            JwtTestKeys.mintRs256(OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
    expectUnauthorizedEnvelope(tampered);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("publicKeyHmacEncodings")
  void algConfusion_publicKeyAsHmac_returns401(String label, byte[] hmacKeyBytes) throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintAlgConfusion(OWNER_ID, hmacKeyBytes));
  }

  static Stream<Arguments> publicKeyHmacEncodings() {
    return Stream.of(
        Arguments.of("raw-DER SPKI bytes", JwtTestKeys.PUBLIC_KEY_DER_A),
        Arguments.of(
            "PEM text without trailing newline",
            JwtTestKeys.PUBLIC_KEY_PEM_A.stripTrailing().getBytes(StandardCharsets.UTF_8)),
        Arguments.of(
            "PEM text with trailing newline",
            JwtTestKeys.PUBLIC_KEY_PEM_A.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void algNone_returns401() throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintNone(OWNER_ID));
  }

  @Test
  void rs384OutOfAllowlist_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs384(OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }

  /** kid is resolved against a fixed in-memory map only — a miss is opaque, no fs/DB/network. */
  @ParameterizedTest(name = "kid={0}")
  @ValueSource(strings = {"../../etc/passwd", "' OR 1=1--", "unknown-kid-2099"})
  void unknownOrMaliciousKid_returns401(String maliciousKid) throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(OWNER_ID, maliciousKid, JwtTestKeys.KEY_PAIR_A));
  }

  /**
   * Mirror of the unknown-kid row signed with the OTHER keypair. With two keys in a salt-randomized
   * immutable map, this makes a "return values().iterator().next()" mutant deterministically
   * caught: whichever key iterates first, its matching-signed token here would verify → 200 → red.
   */
  @Test
  void unknownKid_signedWithKeyPairB_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(OWNER_ID, "unknown-kid-2099", JwtTestKeys.KEY_PAIR_B));
  }

  /**
   * BLOCKER regression: an RS256 token with NO kid header. get(null) on the immutable public-key
   * map throws NPE (unlike HashMap) — which would escape the filter and surface as a 500. Must be a
   * standard 401, indistinguishable from unknown-kid (no cause oracle).
   */
  @Test
  void rs256WithoutKid_returns401() throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintRs256NoKid(OWNER_ID, JwtTestKeys.KEY_PAIR_A));
  }

  /**
   * Residual of the same class as the null-kid blocker: a validly-signed token whose roles claim
   * has a null element. Building the role list post-verification would NPE and escape as a 500 —
   * must be a standard 401 (malformed token, fail-closed), with observability left flat.
   */
  @Test
  void rolesClaimWithNullElement_returns401() throws Exception {
    String token =
        JwtTestKeys.mintRs256WithRoles(
            OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A, Arrays.asList("USER", null));
    expectUnauthorizedEnvelope(token);
  }

  /**
   * Strict {@code Bearer } scheme (#23 class): a valid RS256 token presented under a wrong scheme,
   * wrong case, glued without a space, or with no scheme at all → 401, envelope intact,
   * observability flat (the filter never calls parse() for a non-{@code Bearer } header).
   */
  @ParameterizedTest(name = "auth=[{0}]")
  @MethodSource("nonBearerAuthHeaders")
  void nonBearerScheme_returns401(String label, String authHeaderValue) throws Exception {
    expectUnauthorizedForAuthHeader(authHeaderValue);
  }

  static Stream<Arguments> nonBearerAuthHeaders() {
    String token = JwtTestKeys.mintRs256(OWNER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);
    return Stream.of(
        Arguments.of("Basic scheme", "Basic " + token),
        Arguments.of("Token scheme", "Token " + token),
        Arguments.of("lowercase bearer", "bearer " + token),
        Arguments.of("glued, no space", "Bearer" + token),
        Arguments.of("raw, no scheme", token));
  }

  /**
   * Malformed token under the correct scheme: "Bearer &lt;garbage&gt;" reaches parse() but is not a
   * parseable JWT → 401 with the full 4-field envelope, observability flat. Pins the
   * malformed-token cause through the same assertion machinery as the other abuse rows.
   */
  @Test
  void malformedBearerToken_returns401() throws Exception {
    expectUnauthorizedEnvelope("not-a-real-jwt");
  }

  /**
   * A validly-signed RS256 token whose {@code sub} is not a numeric user id. It is rejected in
   * parse() BEFORE recordAcceptance (counter flat) and never reaches the CurrentUser resolver's
   * Long.valueOf — so it cannot surface as a counter-moved 500. Pins the
   * sub-validation-on-the-accept-path guard.
   */
  @Test
  void validSignatureNonNumericSubject_returns401_observabilityFlat() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256Subject("not-a-number", JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }

  /** Oversized numeric sub (&gt; Long.MAX_VALUE) — a NumberFormatException in the resolver too. */
  @Test
  void validSignatureOversizedSubject_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256Subject(
            "99999999999999999999999999", JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }

  /**
   * IDOR on the new RS256 path: user A holds a fully valid RS256 token but requests user B's order.
   * The token is ACCEPTED (auth succeeds — counter/audit move), then ownership denies access with a
   * 404 that does not leak existence (contract §Order). This proves the sub/roles claims drive
   * authorization on the pinned endpoint, not just authentication.
   */
  @Test
  void otherUsersOrder_withValidRs256_returns404_tokenStillAccepted() throws Exception {
    long attackerId = 99L;
    double acceptedBefore = counterCount("RS256", JwtTestKeys.KID_A);
    int auditBefore = auditLineCount();

    MvcResult result =
        mockMvc
            .perform(
                get(orderPath())
                    .header(
                        "Authorization",
                        "Bearer "
                            + JwtTestKeys.mintRs256(
                                attackerId, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A)))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("ORDER_NOT_FOUND")))
            .andReturn();

    // The 404 must not become an existence oracle. OrderNotFoundException's javadoc states that the
    // message never reveals whether the order is missing or merely someone else's — but nothing
    // enforced it: this row asserted only $.error, so a message naming the order or its owner, or
    // an owner_id field appearing on the envelope, stayed green on the one path that matters.
    // Two non-overlapping guards, neither dominating the other: an added field trips the key set,
    // a talkative message trips the equality. $.path legitimately carries the id (it is the URL the
    // caller already knows), so the guard is scoped to the message and the key set, not the body.
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        ContractShape.keysOf(body),
        "the cross-user 404 envelope must not gain a field that identifies the real owner");
    assertEquals(
        "Order not found",
        body.get("message").asText(),
        "the 404 message must be identical for missing and not-yours — never an existence oracle");

    // A valid token that is authorization-denied is still an ACCEPTED token: the counter/audit
    // move (unlike the 401 abuse rows), which is exactly how a 404-authz differs from a 401-auth.
    assertEquals(acceptedBefore + 1, counterCount("RS256", JwtTestKeys.KID_A), 0.0001);
    assertEquals(auditBefore + 1, auditLineCount());
  }
}
