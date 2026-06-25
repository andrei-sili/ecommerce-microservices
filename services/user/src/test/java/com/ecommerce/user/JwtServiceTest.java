package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private static final String SECRET = "test-jwt-secret-that-is-at-least-32-bytes-long-0123456789";

  private final JwtService jwtService =
      new JwtService(new JwtProperties(SECRET, 900, 604800, "user-service-test"));

  @Test
  void issuedToken_containsExactlyContractClaims_andNoPii() {
    String token = jwtService.issueAccessToken(42L, Set.of("USER"));

    Claims claims =
        Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .build()
            .parseSignedClaims(token)
            .getPayload();

    assertEquals("42", claims.getSubject());
    assertTrue(claims.containsKey("roles"));
    assertTrue(claims.getIssuedAt() != null);
    assertTrue(claims.getExpiration() != null);
    // No PII in the payload.
    assertFalse(claims.containsKey("email"));
    assertFalse(claims.containsKey("name"));
  }

  @Test
  void parse_roundTripsUserAndRoles() {
    String token = jwtService.issueAccessToken(7L, Set.of("ADMIN", "USER"));
    JwtService.AuthenticatedUser user = jwtService.parse(token);
    assertEquals(7L, user.userId());
    assertTrue(user.roles().contains("ADMIN"));
  }

  @Test
  void parse_rejectsTokenSignedWithDifferentSecret() {
    JwtService other =
        new JwtService(
            new JwtProperties(
                "another-secret-that-is-also-at-least-32-bytes-xyz0", 900, 604800, "x"));
    String foreign = other.issueAccessToken(1L, Set.of("USER"));
    assertThrows(Exception.class, () -> jwtService.parse(foreign));
  }

  @Test
  void constructor_rejectsShortSecret() {
    assertThrows(
        IllegalStateException.class,
        () -> new JwtService(new JwtProperties("too-short", 900, 604800, "x")));
  }
}
