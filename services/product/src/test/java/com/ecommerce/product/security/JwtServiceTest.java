package com.ecommerce.product.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.product.config.JwtProperties;
import com.ecommerce.product.support.JwtTestKeys;
import com.ecommerce.product.support.TestJwt;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests of the dual-accept validator in isolation (no filter chain, no HTTP). */
class JwtServiceTest {

  private static final Map<String, String> PUBLIC_KEYS =
      Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A);

  private static JwtService service(List<String> algs) {
    return new JwtService(
        new JwtProperties(TestJwt.SECRET, algs, PUBLIC_KEYS), new SimpleMeterRegistry());
  }

  @Test
  void parsesValidHs256TokenWithRoles() {
    JwtService.AuthenticatedUser user =
        service(List.of("HS256", "RS256")).parse(JwtTestKeys.mintHs256("42"));

    assertThat(user.subject()).isEqualTo("42");
    assertThat(user.roles()).containsExactly("ADMIN");
  }

  @Test
  void parsesValidRs256TokenWithRoles() {
    JwtService.AuthenticatedUser user =
        service(List.of("HS256", "RS256"))
            .parse(JwtTestKeys.mintRs256("7", JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));

    assertThat(user.subject()).isEqualTo("7");
    assertThat(user.roles()).containsExactly("ADMIN");
  }

  @Test
  void rejectsHs256WhenAllowlistIsRs256Only() {
    assertThatThrownBy(() -> service(List.of("RS256")).parse(JwtTestKeys.mintHs256("42")))
        .isInstanceOf(JwtException.class);
  }

  /**
   * Isolates the null-kid guard from the filter: an RS256 token with no kid header must throw a
   * JwtException (not NPE on the immutable public-key map), so the guard stays load-bearing even if
   * the filter's catch is ever widened again.
   */
  @Test
  void parse_rs256WithoutKid_throws() {
    assertThatThrownBy(
            () ->
                service(List.of("HS256", "RS256"))
                    .parse(JwtTestKeys.mintRs256NoKid("7", JwtTestKeys.KEY_PAIR_A)))
        .isInstanceOf(UnsupportedJwtException.class);
  }
}
