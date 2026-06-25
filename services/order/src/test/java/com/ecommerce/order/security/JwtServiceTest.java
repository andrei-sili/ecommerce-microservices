package com.ecommerce.order.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.order.security.JwtService.AuthenticatedUser;
import com.ecommerce.order.support.TestJwt;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private final JwtService jwtService = new JwtService(TestJwt.SECRET);

  @Test
  void parse_validToken_extractsSubjectAndRoles() {
    String token = TestJwt.token("7", List.of("USER"));
    AuthenticatedUser user = jwtService.parse(token);
    assertThat(user.subject()).isEqualTo("7");
    assertThat(user.roles()).containsExactly("USER");
  }

  @Test
  void parse_expiredToken_throws() {
    String expired = TestJwt.expiredToken("7", List.of("USER"));
    assertThatThrownBy(() -> jwtService.parse(expired)).isInstanceOf(JwtException.class);
  }

  @Test
  void parse_tokenSignedWithDifferentSecret_throws() {
    String foreignSecret = "another-secret-also-long-enough-for-hs256-0987654321";
    String foreignToken =
        Jwts.builder()
            .subject("7")
            .claim("roles", List.of("USER"))
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
            .signWith(Keys.hmacShaKeyFor(foreignSecret.getBytes(StandardCharsets.UTF_8)))
            .compact();
    assertThatThrownBy(() -> jwtService.parse(foreignToken)).isInstanceOf(JwtException.class);
  }

  @Test
  void constructor_rejectsShortSecret() {
    assertThatThrownBy(() -> new JwtService("too-short")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void constructor_rejectsBlankSecret() {
    assertThatThrownBy(() -> new JwtService("  ")).isInstanceOf(IllegalStateException.class);
  }
}
