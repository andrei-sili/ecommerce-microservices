package com.ecommerce.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ecommerce.product.support.JwtTestKeys;
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

  private static final String SUBJECT = "1";

  @DynamicPropertySource
  static void rollbackAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "HS256");
  }

  @Test
  void hs256_stillAccepted_countsAndAudits() throws Exception {
    double before = counterCount("HS256", "-");

    expectCreated(JwtTestKeys.mintHs256(SUBJECT));

    assertEquals(before + 1, counterCount("HS256", "-"), 0.0001);
    assertAudited("JWT accepted alg=HS256 kid=-");
  }

  @Test
  void validRs256_whenRs256NotAccepted_returns401() throws Exception {
    expectUnauthorizedEnvelope(
        JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
  }
}
