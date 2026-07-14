package com.ecommerce.user.security;

import com.ecommerce.user.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.MalformedJwtException;
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
 * Issues access tokens and validates inbound tokens with dual-accept (Slice 5e).
 *
 * <p>Signing is config-driven ({@code JWT_SIGNING_ALG}): {@code HS256} (default) signs with the
 * legacy secret; {@code RS256} (phase-2 flip) signs with the mounted RSA private key and stamps
 * {@code kid=user-rs256-2026-07}. Both paths pin the algorithm explicitly ({@code Jwts.SIG.HS256} /
 * {@code Jwts.SIG.RS256}) — never single-arg {@code signWith}, which infers a stronger family on a
 * larger key. The HS256 signing path is kept, not deleted, so a rollback flip is config-only.
 *
 * <p>Validation is an independent flag ({@code JWT_ACCEPTED_ALGS}) and routes by the JOSE {@code
 * alg} header through a pinned map — {@code RS256} → the kid's RSA public key, {@code HS256} → the
 * legacy secret (only while it is in the allowlist), anything else → reject. There is no try/catch
 * fallback and the legacy secret is never derived from public-key bytes, so an {@code alg=HS256}
 * token HMAC-signed with the trusted public PEM (algorithm confusion) verifies against the legacy
 * secret and fails. Claims are exactly {@code iss}, {@code sub}, {@code roles}, {@code iat}, {@code
 * exp} — no PII; the RS256 flip changes only the JOSE header and the signature.
 */
@Service
public class JwtService {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("jwt.audit");

  /**
   * Dated key id stamped on every RS256 token, pinned by contract (§Token shape) and never reused.
   * A future rotation to a new signing kid is a separate contract decision (backlog rotation
   * probe).
   */
  private static final String SIGNING_KID = "user-rs256-2026-07";

  private final SecretKey hmacKey;
  private final Map<String, RSAPublicKey> publicKeysByKid;
  private final boolean hs256Enabled;
  private final boolean rs256Enabled;
  private final boolean signRs256;
  private final JwtParser parser;
  private final MeterRegistry meterRegistry;
  private final long accessTtlSeconds;
  private final String issuer;

  /**
   * The mounted signing key. Loaded and validated at startup unconditionally (user always mounts it
   * in slice 5e), so an RS256 signer flip cannot start without a valid key. Used to sign when
   * {@code signing-alg=RS256}; held otherwise so a rollback flip stays config-only.
   */
  private final RSAPrivateKey signingPrivateKey;

  public JwtService(JwtProperties properties, MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.accessTtlSeconds = properties.accessTokenTtlSeconds();
    this.issuer = properties.issuer();

    Set<String> accepted = normalizeAlgs(properties.acceptedAlgs());
    this.hs256Enabled = accepted.contains("HS256");
    this.rs256Enabled = accepted.contains("RS256");

    // The issuer signs HS256 (default) or RS256 (the phase-2 flip, JWT_SIGNING_ALG=RS256).
    // Any other value fail-fasts instead of silently signing the wrong alg. Signing and
    // validation are independent flags — user validates its own inbound tokens via the locator.
    String signingAlg =
        properties.signingAlg() == null
            ? "HS256"
            : properties.signingAlg().trim().toUpperCase(Locale.ROOT);
    if (!"HS256".equals(signingAlg) && !"RS256".equals(signingAlg)) {
      throw new IllegalStateException(
          "JWT_SIGNING_ALG="
              + properties.signingAlg()
              + " is not supported; the user issuer signs HS256 or RS256 only");
    }
    this.signRs256 = "RS256".equals(signingAlg);

    // hmacKey signs HS256 tokens (while the signer is HS256) and validates them (while HS256 is
    // accepted); it stays required through phase 2. publicKeysByKid validates RS256. The private
    // key signs RS256 once flipped, loaded unconditionally so the flip cannot start keyless.
    this.hmacKey = loadHmacKey(properties.secret(), hs256Enabled);
    this.publicKeysByKid = loadPublicKeys(properties.publicKeys(), rs256Enabled);
    this.signingPrivateKey = RsaPemKeys.loadPrivateKey(properties.privateKeyPath());

    this.parser = Jwts.parser().keyLocator(new AlgKeyLocator()).build();
  }

  public String issueAccessToken(Long userId, Set<String> roles) {
    Instant now = Instant.now();
    JwtBuilder builder =
        Jwts.builder()
            .issuer(issuer)
            .subject(String.valueOf(userId))
            .claim("roles", List.copyOf(roles))
            .issuedAt(java.util.Date.from(now))
            .expiration(java.util.Date.from(now.plusSeconds(accessTtlSeconds)));
    if (signRs256) {
      // Pin RS256 explicitly: single-arg signWith infers RS384/RS512 on a larger key (a 4096-bit
      // key yields RS512), breaking the documented alg and the observability tag. The kid routes
      // validators to the right public key (claims are unchanged — only the header + signature).
      return builder
          .header()
          .keyId(SIGNING_KID)
          .and()
          .signWith(signingPrivateKey, Jwts.SIG.RS256)
          .compact();
    }
    // Pin HS256 explicitly (same inference hazard: a >48-byte secret yields HS384). Kept as the
    // rollback path (JWT_SIGNING_ALG=HS256) — never removed while the two flags stay independent.
    return builder.signWith(hmacKey, Jwts.SIG.HS256).compact();
  }

  /**
   * Verifies signature AND expiry via the dual-accept locator; throws {@link JwtException} on any
   * failure. Records the accepted alg/kid on the counter and the {@code jwt.audit} log.
   */
  public AuthenticatedUser parse(String token) {
    Jws<Claims> jws = parser.parseSignedClaims(token);
    JwsHeader header = jws.getHeader();
    Claims claims = jws.getPayload();

    Long userId = Long.valueOf(claims.getSubject());
    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);
    // A signed-but-malformed roles claim (e.g. "roles":[null]) is a broken token, not an empty role
    // set: reject it as 401 (fail-closed) instead of letting Set.copyOf NPE escape the filter's
    // JwtException catch into a non-contract 500.
    if (roles != null && roles.stream().anyMatch(r -> r == null)) {
      throw new MalformedJwtException("Malformed roles claim");
    }
    Set<String> roleSet = roles == null ? Set.of() : Set.copyOf(roles);

    // Record acceptance only after the token FULLY validates — a token that 401s must not move the
    // counter or emit an audit line (the phase-3 contraction gate reads exactly that signal).
    recordAcceptance(header.getAlgorithm(), header.getKeyId());
    return new AuthenticatedUser(userId, roleSet);
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
        String kid = header.getKeyId();
        // Reject an absent kid BEFORE the map lookup: publicKeysByKid is immutable (Map.copyOf),
        // and get(null) throws NPE (unlike HashMap). That NPE would escape parseSignedClaims (the
        // filter catches only JwtException/IllegalArgumentException) and surface as a 500 —
        // breaking
        // the pinned 401 contract and handing an unauthenticated caller a scriptable 5xx primitive.
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

  public record AuthenticatedUser(Long userId, Set<String> roles) {}
}
