package com.ecommerce.user.security;

import com.ecommerce.user.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 access tokens. Claims are exactly {@code sub}, {@code roles}, {@code
 * iat}, {@code exp} — no email/name or other PII (contract-binding).
 */
@Service
public class JwtService {

  private final SecretKey key;
  private final long accessTtlSeconds;
  private final String issuer;

  public JwtService(JwtProperties properties) {
    byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < 32) {
      throw new IllegalStateException(
          "JWT_SECRET must be at least 32 bytes for HS256; configure a strong secret via env");
    }
    this.key = Keys.hmacShaKeyFor(secretBytes);
    this.accessTtlSeconds = properties.accessTokenTtlSeconds();
    this.issuer = properties.issuer();
  }

  public String issueAccessToken(Long userId, Set<String> roles) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(issuer)
        .subject(String.valueOf(userId))
        .claim("roles", List.copyOf(roles))
        .issuedAt(java.util.Date.from(now))
        .expiration(java.util.Date.from(now.plusSeconds(accessTtlSeconds)))
        .signWith(key)
        .compact();
  }

  /** Verifies signature AND expiry; throws {@link JwtException} on any failure. */
  public AuthenticatedUser parse(String token) {
    Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    Long userId = Long.valueOf(claims.getSubject());
    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);
    return new AuthenticatedUser(userId, roles == null ? Set.of() : Set.copyOf(roles));
  }

  public long getAccessTtlSeconds() {
    return accessTtlSeconds;
  }

  public record AuthenticatedUser(Long userId, Set<String> roles) {}
}
