package com.ecommerce.order.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.order.config.JwtProperties;
import com.ecommerce.order.security.JwtService.AuthenticatedUser;
import com.ecommerce.order.support.JwtTestKeys;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of {@link JwtService#parse} and the acceptance observability, built directly
 * (no Spring context) against runtime-generated keys. The full end-to-end matrix through the real
 * filter chain lives in {@link DualAcceptValidationIntegrationTest} and its siblings.
 */
class JwtServiceTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private JwtService dualAccept() {
    return new JwtService(
        new JwtProperties(
            JwtTestKeys.SECRET,
            List.of("HS256", "RS256"),
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A)),
        meterRegistry);
  }

  private double accepted(String alg, String kid) {
    var counter =
        meterRegistry.find("jwt.accepted.tokens").tag("alg", alg).tag("kid", kid).counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void parse_validHs256_extractsSubjectAndRoles_andCounts() {
    AuthenticatedUser user = dualAccept().parse(JwtTestKeys.mintHs256(7));
    assertThat(user.subject()).isEqualTo("7");
    assertThat(user.roles()).containsExactly("USER");
    assertThat(accepted("HS256", "-")).isEqualTo(1.0);
  }

  @Test
  void parse_validRs256_extractsSubjectAndRoles_andCountsKid() {
    AuthenticatedUser user =
        dualAccept().parse(JwtTestKeys.mintRs256(7, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
    assertThat(user.subject()).isEqualTo("7");
    assertThat(user.roles()).containsExactly("USER");
    assertThat(accepted("RS256", JwtTestKeys.KID_A)).isEqualTo(1.0);
  }

  @Test
  void parse_expiredToken_throws_andDoesNotCount() {
    JwtService service = dualAccept();
    assertThatThrownBy(() -> service.parse(JwtTestKeys.mintHs256(7, -60)))
        .isInstanceOf(JwtException.class);
    assertThat(accepted("HS256", "-")).isEqualTo(0.0);
  }

  @Test
  void parse_wrongKeypairRs256_throws() {
    JwtService service = dualAccept();
    String forged = JwtTestKeys.mintRs256(7, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_WRONG);
    assertThatThrownBy(() -> service.parse(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void parse_rolesClaimWithNullElement_throwsMalformed() {
    JwtService service = dualAccept();
    String token =
        JwtTestKeys.mintRs256WithRoles(
            7, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A, Arrays.asList("USER", null));
    assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
  }

  /**
   * Pins the null-kid guard at the parse level. With the filter's narrow catch ({@code JwtException
   * | IllegalArgumentException}), dropping the guard lets the raw NPE from {@code
   * publicKeysByKid.get(null)} escape as a 500 — so the integration row ({@code
   * rs256WithoutKid_returns401}) turns red too. This unit test kills the same mutant
   * filter-independently: with the guard parse() throws a JwtException, without it a
   * NullPointerException — so removing the guard turns THIS test red.
   */
  @Test
  void parse_rs256WithoutKid_throws() {
    JwtService service = dualAccept();
    String noKid = JwtTestKeys.mintRs256NoKid(7, JwtTestKeys.KEY_PAIR_A);
    assertThatThrownBy(() -> service.parse(noKid)).isInstanceOf(JwtException.class);
  }

  /**
   * Pins the subject guard at the parse level: a validly-signed token with a non-numeric sub throws
   * a JwtException before recordAcceptance (counter untouched), so it can never reach the
   * resolver's Long.valueOf and surface as a counter-moved 500.
   */
  @Test
  void parse_nonNumericSubject_throws_andDoesNotCount() {
    JwtService service = dualAccept();
    String badSub =
        JwtTestKeys.mintRs256Subject("not-a-number", JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);
    assertThatThrownBy(() -> service.parse(badSub)).isInstanceOf(JwtException.class);
    assertThat(accepted("RS256", JwtTestKeys.KID_A)).isEqualTo(0.0);
  }
}
