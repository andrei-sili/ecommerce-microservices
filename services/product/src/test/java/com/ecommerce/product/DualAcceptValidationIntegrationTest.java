package com.ecommerce.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ecommerce.product.support.JwtTestKeys;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Slice 5e phase-1 validation matrix under the {@code HS256,RS256} allowlist, through the real
 * filter chain on the pinned protected endpoint {@code POST /api/v1/products}. Every happy row
 * asserts the {@code jwt.accepted.tokens} counter tag AND the {@code jwt.audit} line; every abuse
 * row asserts the pinned four-field 401 envelope (indistinguishable causes) with observability left
 * flat.
 */
class DualAcceptValidationIntegrationTest extends AbstractDualAcceptTest {

  private static final String SUBJECT = "1";

  @Test
  void happyLegacyHs256_returns201_countsAndAudits() throws Exception {
    double before = counterCount("HS256", "-");

    expectCreated(JwtTestKeys.mintHs256(SUBJECT));

    assertEquals(before + 1, counterCount("HS256", "-"), 0.0001);
    assertAudited("JWT accepted alg=HS256 kid=-");
  }

  @Test
  void happyRs256_returns201_countsKid() throws Exception {
    double before = counterCount("RS256", JwtTestKeys.KID_A);

    expectCreated(JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));

    assertEquals(before + 1, counterCount("RS256", JwtTestKeys.KID_A), 0.0001);
    assertAudited("JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_A);
  }

  @Test
  void twoKidCoexistence_bothAccepted_withDistinctKidTags() throws Exception {
    double beforeA = counterCount("RS256", JwtTestKeys.KID_A);
    double beforeB = counterCount("RS256", JwtTestKeys.KID_B);

    expectCreated(JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
    expectCreated(JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_B, JwtTestKeys.KEY_PAIR_B));

    assertEquals(beforeA + 1, counterCount("RS256", JwtTestKeys.KID_A), 0.0001);
    assertEquals(beforeB + 1, counterCount("RS256", JwtTestKeys.KID_B), 0.0001);
    assertAudited("JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_A);
    assertAudited("JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_B);
  }

  /** Extra authorization row: a valid RS256 token whose roles lack ADMIN → 403, roles parsed. */
  @Test
  void validRs256WithoutAdminRole_returns403() throws Exception {
    double before = counterCount("RS256", JwtTestKeys.KID_A);

    expectForbiddenEnvelope(
        JwtTestKeys.mintRs256Roles(
            SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A, List.of("USER")));

    // The token IS accepted (roles parsed on the new path) — only authorization fails.
    assertEquals(before + 1, counterCount("RS256", JwtTestKeys.KID_A), 0.0001);
  }

  @Test
  void wrongKeypair_returns401() throws Exception {
    // Valid kid but signed with a different private key → signature verification fails.
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_WRONG));
  }

  @Test
  void expiredRs256_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A, -120));
  }

  @Test
  void tamperedRs256Signature_returns401() throws Exception {
    String tampered =
        JwtTestKeys.tamperSignature(
            JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
    expectUnauthorizedEnvelope(tampered);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("publicKeyHmacEncodings")
  void algConfusion_publicKeyAsHmac_returns401(String label, byte[] hmacKeyBytes) throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintAlgConfusion(SUBJECT, hmacKeyBytes));
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
    expectUnauthorizedEnvelope(JwtTestKeys.mintNone(SUBJECT));
  }

  @Test
  void rs384OutOfAllowlist_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs384(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }

  /** kid is resolved against a fixed in-memory map only — a miss is opaque, no fs/DB/network. */
  @ParameterizedTest(name = "kid={0}")
  @ValueSource(strings = {"../../etc/passwd", "' OR 1=1--", "unknown-kid-2099"})
  void unknownOrMaliciousKid_returns401(String maliciousKid) throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(SUBJECT, maliciousKid, JwtTestKeys.KEY_PAIR_A));
  }

  /**
   * Mirror of the unknown-kid row signed with the OTHER keypair. With two keys in a salt-randomized
   * immutable map, this makes a "return values().iterator().next()" mutant deterministically
   * caught: whichever key iterates first, its matching-signed token here would verify → 201 → red.
   */
  @Test
  void unknownKid_signedWithKeyPairB_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(SUBJECT, "unknown-kid-2099", JwtTestKeys.KEY_PAIR_B));
  }

  /**
   * BLOCKER regression: an RS256 token with NO kid header. get(null) on the immutable public-key
   * map throws NPE (unlike HashMap) — which would escape the filter and surface as a 500. Must be a
   * standard 401, indistinguishable from unknown-kid (no cause oracle).
   */
  @Test
  void rs256WithoutKid_returns401() throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintRs256NoKid(SUBJECT, JwtTestKeys.KEY_PAIR_A));
  }

  /**
   * Residual of the same class as the null-kid blocker: a validly-signed token whose roles claim
   * has a null element. Set.copyOf(roles) NPEs post-verification and would escape as a 500 — must
   * be a standard 401 (malformed token, fail-closed), with observability left flat.
   */
  @Test
  void rolesClaimWithNullElement_returns401() throws Exception {
    String token =
        JwtTestKeys.mintRs256Roles(
            SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A, Arrays.asList("ADMIN", null));
    expectUnauthorizedEnvelope(token);
  }

  @Test
  void anonymousProductList_isPublic_returns200() throws Exception {
    expectPublicGetOk("/api/v1/products");
  }

  @Test
  void anonymousCategoryList_isPublic_returns200() throws Exception {
    expectPublicGetOk("/api/v1/categories");
  }
}
