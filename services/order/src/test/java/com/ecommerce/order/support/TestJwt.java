package com.ecommerce.order.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;

/** Mints HS256 tokens that mirror the User Service contract for filter/authorization tests. */
public final class TestJwt {

  public static final String SECRET = "test-secret-that-is-long-enough-for-hs256-1234567890";

  private TestJwt() {}

  public static String token(String subject, List<String> roles) {
    return token(subject, roles, Instant.now().plus(15, ChronoUnit.MINUTES));
  }

  public static String expiredToken(String subject, List<String> roles) {
    return token(subject, roles, Instant.now().minus(1, ChronoUnit.MINUTES));
  }

  public static String token(String subject, List<String> roles, Instant expiry) {
    SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    return Jwts.builder()
        .subject(subject)
        .claim("roles", roles)
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(expiry))
        // Pin HS256 explicitly: single-arg signWith infers the strongest alg the 52-byte secret
        // allows (HS384), which the Slice 5e dual-accept keyLocator rejects (HS256-only branch).
        // The User Service issues HS256, so these fixtures must mirror that alg.
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  public static String bearer(String token) {
    return "Bearer " + token;
  }
}
