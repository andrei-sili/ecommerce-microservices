package com.ecommerce.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ecommerce.product.support.JwtTestKeys;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Allowlist contraction: with {@code accepted-algs=RS256}, a fresh legacy HS256 token is rejected
 * and algorithm-confusion is closed via the HS256 branch being disabled entirely (a different
 * mechanism than the dual build, where it fails signature verification). Proves the phase-3 posture
 * ahead of time (the alg-confusion rows are required in BOTH builds by contract).
 */
class Rs256OnlyValidationIntegrationTest extends AbstractDualAcceptTest {

  private static final String SUBJECT = "1";

  @DynamicPropertySource
  static void contractAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "RS256");
  }

  @Test
  void rs256_stillAccepted_provingConfigIsLive() throws Exception {
    expectCreated(JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }

  /**
   * Positive control for the {@code jwt.accepted.tokens} signal, paired with its negative in ONE
   * method.
   *
   * <p>Every abuse row in this suite asserts that the counter did NOT move and no audit line was
   * emitted. That assertion is satisfied just as well by an instrument that never moves at all — a
   * dead counter, an unregistered meter, an audit logger detached by a context refresh — so the
   * whole negative signal can go vacuous without a single test turning red. Moving the counter by
   * exactly 1.0 first, then showing it flat across a rejection, is what makes the negative
   * meaningful: the instrument is demonstrably live in the same context, in the same method.
   */
  @Test
  void acceptedTokenMovesTheCounterByOne_rejectedTokenLeavesItFlat() throws Exception {
    double acceptedBefore = totalAccepted();
    int auditBefore = auditLineCount();

    expectCreated(JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));

    assertEquals(
        acceptedBefore + 1.0,
        totalAccepted(),
        0.0001,
        "an accepted RS256 token must move jwt.accepted.tokens by exactly 1.0");
    assertEquals(
        auditBefore + 1,
        auditLineCount(),
        "an accepted RS256 token must emit exactly one jwt.audit line");
    assertAudited("JWT accepted alg=RS256 kid=" + JwtTestKeys.KID_A);

    double acceptedAfterPositive = totalAccepted();
    int auditAfterPositive = auditLineCount();

    // Same context, same method: a rejected token must leave both signals untouched.
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_WRONG));

    assertEquals(acceptedAfterPositive, totalAccepted(), 0.0001);
    assertEquals(auditAfterPositive, auditLineCount());
  }

  @Test
  void freshHs256_afterContraction_returns401() throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintHs256(SUBJECT));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("publicKeyHmacEncodings")
  void algConfusion_underRs256Only_returns401(String label, byte[] hmacKeyBytes) throws Exception {
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
}
