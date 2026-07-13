package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.security.JwtService;
import com.ecommerce.user.support.JwtTestKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private static final String SECRET = JwtTestKeys.SECRET;
  private static final MeterRegistry REGISTRY = new SimpleMeterRegistry();

  private final JwtService jwtService = new JwtService(props(SECRET), REGISTRY);

  private static JwtProperties props(String secret) {
    return new JwtProperties(
        secret,
        900,
        604800,
        "user-service-test",
        "HS256",
        List.of("HS256", "RS256"),
        JwtTestKeys.PRIVATE_KEY_PATH_A,
        Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A));
  }

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
        new JwtService(props("another-secret-that-is-also-at-least-32-bytes-xyz0"), REGISTRY);
    String foreign = other.issueAccessToken(1L, Set.of("USER"));
    assertThrows(Exception.class, () -> jwtService.parse(foreign));
  }

  @Test
  void constructor_rejectsShortSecret() {
    assertThrows(IllegalStateException.class, () -> new JwtService(props("too-short"), REGISTRY));
  }
}
