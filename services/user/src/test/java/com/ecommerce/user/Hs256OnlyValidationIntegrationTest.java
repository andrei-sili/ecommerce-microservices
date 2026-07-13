package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ecommerce.user.support.JwtTestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase-1 rollback lever: with {@code accepted-algs=HS256} (JWT_ACCEPTED_ALGS rolled back), a valid
 * RS256 token is rejected while legacy HS256 still works. Mirrors {@link
 * Rs256OnlyValidationIntegrationTest} in the opposite direction so the {@code rs256Enabled} guard
 * in the locator is killed by a real test — the contract pins BOTH flags as independently
 * rollbackable.
 */
class Hs256OnlyValidationIntegrationTest extends AbstractDualAcceptTest {

  @DynamicPropertySource
  static void rollbackAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "HS256");
  }

  @Test
  void hs256_stillAccepted_countsAndAudits() throws Exception {
    long userId = registerUser("hs256only@example.com", "Hs256Only");
    double before = counterCount("HS256", "-");

    expectProfileOk(JwtTestKeys.mintHs256(userId), "hs256only@example.com");

    assertEquals(before + 1, counterCount("HS256", "-"), 0.0001);
    assertAudited("JWT accepted alg=HS256 kid=-");
  }

  @Test
  void validRs256_whenRs256NotAccepted_returns401() throws Exception {
    long userId = registerUser("rs256rejected@example.com", "Rs256Rejected");
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(userId, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }
}
