package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.payment.config.JwtProperties;
import com.ecommerce.payment.security.JwtService;
import com.ecommerce.payment.support.JwtTestKeys;
import io.jsonwebtoken.UnsupportedJwtException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the null-kid guard at the PARSE seam directly (belt-and-suspenders alongside the end-to-end
 * row {@code rs256WithoutKid_returns401}). {@code JwtAuthenticationFilter} now catches only {@code
 * JwtException | IllegalArgumentException}, so removing the guard makes the NPE from {@code
 * Map.copyOf().get(null)} escape as a non-contract 500; this test discriminates the guard one layer
 * earlier by calling {@code parse} directly: with the guard it throws {@link
 * UnsupportedJwtException}; remove it and {@code parse} throws NPE instead, turning this RED.
 */
class JwtServiceParseGuardTest {

  private static JwtService dualAcceptService() {
    JwtProperties props =
        new JwtProperties(
            JwtTestKeys.SECRET,
            List.of("HS256", "RS256"),
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A));
    return new JwtService(props, new SimpleMeterRegistry());
  }

  @Test
  void parse_rs256WithoutKid_throwsUnsupportedJwt_notNpe() {
    // Validly-signed RS256 token with NO kid header: the locator must reject it before the
    // immutable
    // map lookup that would otherwise NPE.
    String noKid = JwtTestKeys.mintRs256NoKid("7", JwtTestKeys.KEY_PAIR_A);

    assertThatThrownBy(() -> dualAcceptService().parse(noKid))
        .isInstanceOf(UnsupportedJwtException.class);
  }
}
