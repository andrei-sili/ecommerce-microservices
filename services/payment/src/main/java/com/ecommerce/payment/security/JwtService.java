package com.ecommerce.payment.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates HS256 access tokens issued by the User Service using the shared {@code JWT_SECRET}.
 * Payment never issues tokens. Signature and expiry are both enforced by the parser.
 */
@Component
public class JwtService {

  private static final int MIN_SECRET_BYTES = 32;

  private final SecretKey key;

  public JwtService(@Value("${security.jwt.secret}") String secret) {
    if (!StringUtils.hasText(secret)) {
      throw new IllegalStateException("JWT_SECRET must be configured");
    }
    byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
    }
    this.key = Keys.hmacShaKeyFor(secretBytes);
  }

  public AuthenticatedUser parse(String token) {
    Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    String subject = claims.getSubject();
    List<String> roles = extractRoles(claims);
    return new AuthenticatedUser(subject, roles);
  }

  @SuppressWarnings("unchecked")
  private List<String> extractRoles(Claims claims) {
    Object raw = claims.get("roles");
    if (raw instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of();
  }

  public record AuthenticatedUser(String subject, List<String> roles) {}
}
