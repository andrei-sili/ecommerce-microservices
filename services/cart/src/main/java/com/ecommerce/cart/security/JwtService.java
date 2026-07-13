package com.ecommerce.cart.security;

import com.ecommerce.cart.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates inbound access tokens issued by the User Service with dual-accept (Slice 5e phase 1).
 * Cart never issues tokens.
 *
 * <p>Validation routes by the JOSE {@code alg} header through a pinned map — {@code RS256} → the
 * kid's RSA public key, {@code HS256} → the legacy secret (only while it is in the allowlist),
 * anything else → reject. There is no try/catch fallback and the legacy secret is never derived
 * from public-key bytes, so an {@code alg=HS256} token HMAC-signed with the trusted public PEM
 * (algorithm confusion) verifies against the legacy secret and fails.
 */
@Component
public class JwtService {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("jwt.audit");

  private final SecretKey hmacKey;
  private final Map<String, RSAPublicKey> publicKeysByKid;
  private final boolean hs256Enabled;
  private final boolean rs256Enabled;
  private final JwtParser parser;
  private final MeterRegistry meterRegistry;

  public JwtService(JwtProperties properties, MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;

    Set<String> accepted = normalizeAlgs(properties.acceptedAlgs());
    this.hs256Enabled = accepted.contains("HS256");
    this.rs256Enabled = accepted.contains("RS256");

    this.hmacKey = loadHmacKey(properties.secret(), hs256Enabled);
    this.publicKeysByKid = loadPublicKeys(properties.publicKeys(), rs256Enabled);

    this.parser = Jwts.parser().keyLocator(new AlgKeyLocator()).build();
  }

  /**
   * Verifies signature AND expiry via the dual-accept locator; throws {@link
   * io.jsonwebtoken.JwtException} on any failure. Records the accepted alg/kid on the counter and
   * the {@code jwt.audit} log.
   */
  public AuthenticatedUser parse(String token) {
    Jws<Claims> jws = parser.parseSignedClaims(token);
    JwsHeader header = jws.getHeader();
    Claims claims = jws.getPayload();

    String subject = claims.getSubject();
    List<String> roles = extractRoles(claims);

    // Record acceptance only after the token FULLY validates (signature, expiry, well-formed roles)
    // — a token that 401s must not move the counter or emit an audit line (the phase-3 contraction
    // gate reads exactly that signal).
    recordAcceptance(header.getAlgorithm(), header.getKeyId());
    return new AuthenticatedUser(subject, roles);
  }

  @SuppressWarnings("unchecked")
  private static List<String> extractRoles(Claims claims) {
    Object raw = claims.get("roles");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    // A signed-but-malformed roles claim (e.g. "roles":[null]) is a broken token, not an empty role
    // set: reject it as 401 (fail-closed) instead of minting a "ROLE_null" authority or letting a
    // downstream copy NPE escape the filter's JwtException catch into a non-contract 500.
    if (list.stream().anyMatch(r -> r == null)) {
      throw new MalformedJwtException("Malformed roles claim");
    }
    return list.stream().map(String::valueOf).toList();
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
    if (!hs256Enabled) {
      // HS256 disabled (post-contraction / rollback to RS256-only): cart never signs, so the legacy
      // secret is not needed and the locator never returns it.
      return null;
    }
    byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < 32) {
      throw new IllegalStateException(
          "JWT_SECRET must be at least 32 bytes for HS256 (required because HS256 is in"
              + " JWT_ACCEPTED_ALGS); configure a strong secret via env");
    }
    return Keys.hmacShaKeyFor(secretBytes);
  }

  private static Map<String, RSAPublicKey> loadPublicKeys(
      Map<String, String> keyPaths, boolean rs256Enabled) {
    if (!rs256Enabled) {
      // RS256 not accepted (e.g. HS256 rollback): the public keys are neither needed nor loaded, so
      // an absent/invalid RS256 key mount cannot block an HS256-only deployment (independent
      // flags).
      return Map.of();
    }
    Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
    if (keyPaths != null) {
      keyPaths.forEach((kid, path) -> keys.put(kid, RsaPemKeys.loadPublicKey(kid, path)));
    }
    if (keys.isEmpty()) {
      throw new IllegalStateException(
          "RS256 is in JWT_ACCEPTED_ALGS but no public key is configured under"
              + " security.jwt.public-keys");
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
        String kid = header.getKeyId();
        // Reject an absent kid BEFORE the map lookup: publicKeysByKid is immutable (Map.copyOf),
        // and get(null) throws NPE (unlike HashMap). That NPE would escape parseSignedClaims (the
        // filter catches only JwtException/IllegalArgumentException) and surface as a 500 —
        // breaking the pinned 401 contract and handing an unauthenticated caller a scriptable 5xx.
        if (kid == null || kid.isBlank()) {
          throw new UnsupportedJwtException("Unknown or unaccepted JWT key id");
        }
        RSAPublicKey key = publicKeysByKid.get(kid);
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

  public record AuthenticatedUser(String subject, List<String> roles) {}
}
