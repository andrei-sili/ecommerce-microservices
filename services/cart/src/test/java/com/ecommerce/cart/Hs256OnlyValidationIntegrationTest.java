package com.ecommerce.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ecommerce.cart.support.JwtTestKeys;
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

  private static final long USER_ID = 7L;

  @DynamicPropertySource
  static void rollbackAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "HS256");
  }

  @Test
  void hs256_stillAccepted_countsAndAudits() throws Exception {
    double before = counterCount("HS256", "-");

    expectCartOk(JwtTestKeys.mintHs256(USER_ID), USER_ID);

    assertEquals(before + 1, counterCount("HS256", "-"), 0.0001);
    assertAudited("JWT accepted alg=HS256 kid=-");
  }

  /** The allowlist here excludes RS256, so the flat rows' positive control must be HS256. */
  @Override
  protected String acceptedToken() {
    return JwtTestKeys.mintHs256(CONTROL_USER_ID);
  }

  @Test
  void validRs256_whenRs256NotAccepted_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(USER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }
}
