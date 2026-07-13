package com.ecommerce.user.security;

import com.ecommerce.user.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues HS256 access tokens and validates inbound tokens with dual-accept (Slice 5e phase 1).
 *
 * <p>Signing stays HS256 (the RS256 flip is phase 2). Validation routes by the JOSE {@code alg}
 * header through a pinned map — {@code RS256} → the kid's RSA public key, {@code HS256} → the
 * legacy secret (only while it is in the allowlist), anything else → reject. There is no try/catch
 * fallback and the legacy secret is never derived from public-key bytes, so an {@code alg=HS256}
 * token HMAC-signed with the trusted public PEM (algorithm confusion) verifies against the legacy
 * secret and fails. Claims are exactly {@code sub}, {@code roles}, {@code iat}, {@code exp} — no
 * PII.
 */
@Service
public class JwtService {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("jwt.audit");

  private final SecretKey hmacKey;
  private final Map<String, RSAPublicKey> publicKeysByKid;
  private final boolean hs256Enabled;
  private final boolean rs256Enabled;
  private final JwtParser parser;
  private final MeterRegistry meterRegistry;
  private final long accessTtlSeconds;
  private final String issuer;

  /**
   * The mounted signing key, validated at startup so the phase-2 RS256 signer flip is config-only.
   * Held (not used) while {@code signing-alg} stays HS256; issuance still uses {@link #hmacKey}.
   */
  @SuppressWarnings("unused")
  private final RSAPrivateKey signingPrivateKey;

  public JwtService(JwtProperties properties, MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.accessTtlSeconds = properties.accessTokenTtlSeconds();
    this.issuer = properties.issuer();

    Set<String> accepted = normalizeAlgs(properties.acceptedAlgs());
    this.hs256Enabled = accepted.contains("HS256");
    this.rs256Enabled = accepted.contains("RS256");

    boolean signsHs256 =
        properties.signingAlg() == null || "HS256".equalsIgnoreCase(properties.signingAlg().trim());
    this.hmacKey =
        (hs256Enabled || signsHs256) ? loadHmacKey(properties.secret(), hs256Enabled) : null;
    this.publicKeysByKid = loadPublicKeys(properties.publicKeys(), rs256Enabled);
    this.signingPrivateKey = RsaPemKeys.loadPrivateKey(properties.privateKeyPath());

    this.parser = Jwts.parser().keyLocator(new AlgKeyLocator()).build();
  }

  public String issueAccessToken(Long userId, Set<String> roles) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(issuer)
        .subject(String.valueOf(userId))
        .claim("roles", List.copyOf(roles))
        .issuedAt(java.util.Date.from(now))
        .expiration(java.util.Date.from(now.plusSeconds(accessTtlSeconds)))
        // Pin HS256 explicitly: single-arg signWith infers the strongest alg the key allows
        // (a >48-byte secret yields HS384), which would break the documented HS256 contract and
        // the alg observability tag. The RS256 flip is phase 2.
        .signWith(hmacKey, Jwts.SIG.HS256)
        .compact();
  }

  /**
   * Verifies signature AND expiry via the dual-accept locator; throws {@link JwtException} on any
   * failure. Records the accepted alg/kid on the counter and the {@code jwt.audit} log.
   */
  public AuthenticatedUser parse(String token) {
    Jws<Claims> jws = parser.parseSignedClaims(token);
    JwsHeader header = jws.getHeader();
    Claims claims = jws.getPayload();
    recordAcceptance(header.getAlgorithm(), header.getKeyId());

    Long userId = Long.valueOf(claims.getSubject());
    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);
    return new AuthenticatedUser(userId, roles == null ? Set.of() : Set.copyOf(roles));
  }

  public long getAccessTtlSeconds() {
    return accessTtlSeconds;
  }

  private void recordAcceptance(String alg, String kid) {
    // kid participates only in RS256 rotation; for RS256 it is an allowlist key resolved by the
    // locator (bounded, injection-safe). HS256 has no kid → "-". This bounds tag cardinality.
    String kidTag = "RS256".equals(alg) && kid != null && !kid.isBlank() ? kid : "-";
    meterRegistry.counter("jwt.accepted.tokens", "alg", alg, "kid", kidTag).increment();
    AUDIT_LOG.info("JWT accepted alg={} kid={}", alg, kidTag);
  }

  private static Set<String> normalizeAlgs(List<String> algs) {
    if (algs == null || algs.isEmpty()) {
      throw new IllegalStateException(
          "JWT_ACCEPTED_ALGS must list at least one algorithm (e.g. HS256,RS256)");
    }
    Set<String> normalized = new java.util.HashSet<>();
    for (String alg : algs) {
      if (alg != null && !alg.isBlank()) {
        normalized.add(alg.trim().toUpperCase(Locale.ROOT));
      }
    }
    if (normalized.isEmpty()) {
      throw new IllegalStateException(
          "JWT_ACCEPTED_ALGS must list at least one algorithm (e.g. HS256,RS256)");
    }
    return normalized;
  }

  private static SecretKey loadHmacKey(String secret, boolean hs256Enabled) {
    byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < 32) {
      String reason =
          hs256Enabled ? "HS256 is in JWT_ACCEPTED_ALGS" : "the signer still issues HS256";
      throw new IllegalStateException(
          "JWT_SECRET must be at least 32 bytes for HS256 (required because "
              + reason
              + "); configure a strong secret via env");
    }
    return Keys.hmacShaKeyFor(secretBytes);
  }

  private static Map<String, RSAPublicKey> loadPublicKeys(
      Map<String, String> keyPaths, boolean rs256Enabled) {
    Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
    if (keyPaths != null) {
      keyPaths.forEach((kid, path) -> keys.put(kid, RsaPemKeys.loadPublicKey(kid, path)));
    }
    if (rs256Enabled && keys.isEmpty()) {
      throw new IllegalStateException(
          "RS256 is in JWT_ACCEPTED_ALGS but no public key is configured under security.jwt.public-keys");
    }
    return Map.copyOf(keys);
  }

  /**
   * Routes verification by the JOSE {@code alg} header through the pinned map. {@code alg=none} is
   * rejected upstream by {@code parseSignedClaims} (jjwt disables unsecured JWTs by default; {@code
   * enableUnsecured()} is never called), so it never reaches this locator.
   */
  private final class AlgKeyLocator extends LocatorAdapter<Key> {
    @Override
    protected Key locate(JwsHeader header) {
      String alg = header.getAlgorithm();
      if ("RS256".equals(alg) && rs256Enabled) {
        RSAPublicKey key = publicKeysByKid.get(header.getKeyId());
        if (key == null) {
          throw new UnsupportedJwtException("Unknown or unaccepted JWT key id");
        }
        return key;
      }
      if ("HS256".equals(alg) && hs256Enabled) {
        return hmacKey;
      }
      throw new UnsupportedJwtException("Unsupported or unaccepted JWT algorithm");
    }
  }

  public record AuthenticatedUser(Long userId, Set<String> roles) {}
}
