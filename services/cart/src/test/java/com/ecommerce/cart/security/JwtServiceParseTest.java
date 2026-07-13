package com.ecommerce.cart.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.cart.config.JwtProperties;
import com.ecommerce.cart.support.JwtTestKeys;
import com.ecommerce.cart.support.TestJwt;
import io.jsonwebtoken.UnsupportedJwtException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parse-level guard tests that the full-filter suites CANNOT discriminate: {@link
 * JwtAuthenticationFilter} catches {@code Exception} broadly, so an NPE from a missing guard would
 * still surface as a clean 401 — masking whether the guard fired at all. Asserting the exception
 * TYPE here (no filter in the loop) kills that surviving mutant.
 */
class JwtServiceParseTest {

  private static JwtService dualAcceptService() {
    JwtProperties props =
        new JwtProperties(
            TestJwt.SECRET,
            List.of("HS256", "RS256"),
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A));
    return new JwtService(props, new SimpleMeterRegistry());
  }

  /**
   * BLOCKER isolation: a validly-signed RS256 token with NO kid must be rejected by the null-kid
   * guard BEFORE the immutable-map lookup ({@code get(null)} NPEs). Pinned at parse level because
   * the filter's broad catch would otherwise convert that NPE into an indistinguishable 401 — the
   * full-filter row {@code rs256WithoutKid_returns401} survives removing the guard; this one does
   * not (drop the guard → NPE, not UnsupportedJwtException → red).
   */
  @Test
  void parse_rs256WithoutKid_throwsUnsupportedJwt_notNpe() {
    JwtService service = dualAcceptService();
    assertThatThrownBy(() -> service.parse(JwtTestKeys.mintRs256NoKid(7L, JwtTestKeys.KEY_PAIR_A)))
        .isInstanceOf(UnsupportedJwtException.class);
  }
}
