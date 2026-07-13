package com.ecommerce.cart;

import com.ecommerce.cart.support.JwtTestKeys;
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

  private static final long USER_ID = 7L;

  @DynamicPropertySource
  static void contractAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "RS256");
  }

  @Test
  void rs256_stillAccepted_provingConfigIsLive() throws Exception {
    expectCartOk(
        JwtTestKeys.mintRs256(USER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A), USER_ID);
  }

  @Test
  void freshHs256_afterContraction_returns401() throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintHs256(USER_ID));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("publicKeyHmacEncodings")
  void algConfusion_underRs256Only_returns401(String label, byte[] hmacKeyBytes) throws Exception {
    expectUnauthorizedEnvelope(JwtTestKeys.mintAlgConfusion(USER_ID, hmacKeyBytes));
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
