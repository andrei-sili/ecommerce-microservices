package com.ecommerce.payment.support;

import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Mints tokens that mirror the User Service contract for the filter/authorisation/seam tests.
 *
 * <p>Slice 5e migrated the fixture off the shared HS256 secret constant to runtime-generated RS256
 * keys ({@link JwtTestKeys}): every token here is a valid RS256 token signed with {@code
 * KEY_PAIR_A} under {@code KID_A}, so the dependent suites now exercise the RS256 validation path
 * end to end. The full dual-accept matrix (HS256 legacy path, alg-confusion, fail-fast, …) lives in
 * the dedicated validation suites.
 */
public final class TestJwt {

  private TestJwt() {}

  public static String token(String subject, List<String> roles) {
    return token(subject, roles, Instant.now().plus(15, ChronoUnit.MINUTES));
  }

  public static String expiredToken(String subject, List<String> roles) {
    return token(subject, roles, Instant.now().minus(1, ChronoUnit.MINUTES));
  }

  public static String token(String subject, List<String> roles, Instant expiry) {
    return Jwts.builder()
        .header()
        .keyId(JwtTestKeys.KID_A)
        .and()
        .issuer("user-service")
        .subject(subject)
        .claim("roles", roles)
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(expiry))
        .signWith(JwtTestKeys.KEY_PAIR_A.getPrivate(), Jwts.SIG.RS256)
        .compact();
  }

  public static String bearer(String token) {
    return "Bearer " + token;
  }
}
