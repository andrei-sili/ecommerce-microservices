package com.ecommerce.product.security;

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
 * Product never issues tokens. Signature and expiry are both enforced by the parser; an invalid or
 * expired token raises a {@link io.jsonwebtoken.JwtException}.
 */
@Component
public class JwtService {

  private final SecretKey key;

  public JwtService(@Value("${security.jwt.secret}") String secret) {
    if (!StringUtils.hasText(secret)) {
      throw new IllegalStateException("JWT_SECRET must be configured");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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
