package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JwtServiceTest {

  private static final String SECRET = JwtTestKeys.SECRET;
  private static final MeterRegistry REGISTRY = new SimpleMeterRegistry();

  private static final String SIGNING_KID = "user-rs256-2026-07";

  private final JwtService jwtService = new JwtService(props(SECRET), REGISTRY);

  private static JwtProperties props(String secret) {
    return props(secret, "HS256", JwtTestKeys.PRIVATE_KEY_PATH_A);
  }

  private static JwtProperties props(String secret, String signingAlg, String privateKeyPath) {
    return new JwtProperties(
        secret,
        900,
        604800,
        "user-service-test",
        signingAlg,
        List.of("HS256", "RS256"),
        privateKeyPath,
        Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A));
  }

  private static String headerAlg(String token) throws Exception {
    return decodeHeader(token).get("alg").asText();
  }

  private static JsonNode decodeHeader(String token) throws Exception {
    String headerJson =
        new String(
            Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
            StandardCharsets.UTF_8);
    JsonNode header = new ObjectMapper().readTree(headerJson);
    assertNotNull(header.get("alg"), "token header must carry an alg key");
    return header;
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

    // Claim parity is contract-binding: EXACTLY these five keys, nothing else — a future
    // claim("userEmail", ...) (PII under a non-blacklisted name) or a stray extra claim must fail
    // here, not slip through a "no email/name" spot check.
    assertEquals(Set.of("iss", "sub", "roles", "iat", "exp"), claims.keySet());
    assertEquals("user-service-test", claims.get("iss"), "iss must equal the configured issuer");
    assertEquals("42", claims.getSubject());
    assertEquals(List.of("USER"), claims.get("roles"));
    assertTrue(claims.getIssuedAt() != null);
    assertTrue(claims.getExpiration() != null);
  }

  @Test
  void issuedTokenHeader_pinsHs256_regardlessOfKeyStrength() throws Exception {
    // Decode the JOSE header of the token emitted by the REAL service path. verifyWith(SecretKey)
    // accepts HS256 OR HS384 on a 456-bit secret and discards the header, so it cannot catch a
    // silent revert to single-arg signWith (which would infer HS384 → every consumer 401s, wrong
    // observability tag, all other tests still green). This pins the emitted alg directly.
    String token = jwtService.issueAccessToken(42L, Set.of("USER"));
    JsonNode header = decodeHeader(token);
    String alg = header.get("alg").asText();
    assertEquals("HS256", alg, "issuer must pin HS256 explicitly (phase 1); the RS256 flip is P2");
    // A refactor hoisting the kid stamp out of the RS256 branch would keep alg=HS256 (suite green)
    // yet pollute the {alg,kid} Prometheus signal the phase-3 contraction gate reads — pin its
    // absence.
    assertNull(
        header.get("kid"),
        "HS256 tokens must NOT carry a kid — the {alg,kid} signal feeds the phase-3 contraction gate");
  }

  @Test
  void issuedRs256TokenHeader_pinsRs256AndKid() throws Exception {
    // Phase-2 flip: with signing-alg=RS256 the JOSE header must carry alg=RS256 AND the pinned kid.
    JwtService rs256 =
        new JwtService(props(SECRET, "RS256", JwtTestKeys.PRIVATE_KEY_PATH_A), REGISTRY);
    String token = rs256.issueAccessToken(42L, Set.of("USER"));

    String headerJson =
        new String(
            Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
            StandardCharsets.UTF_8);
    var header = new ObjectMapper().readTree(headerJson);
    assertNotNull(header.get("alg"), "RS256 token header must carry an alg key");
    assertEquals("RS256", header.get("alg").asText(), "signer must pin RS256 explicitly");
    assertNotNull(header.get("kid"), "RS256 token header must carry a kid key");
    assertEquals(
        SIGNING_KID, header.get("kid").asText(), "every RS256 token carries the pinned kid");
  }

  @Test
  void issuedRs256Token_pinsRs256_evenWith4096BitKey() throws Exception {
    // Inference-drift pin: single-arg signWith would infer RS512 on a 4096-bit key. Only an
    // explicit Jwts.SIG.RS256 keeps the header RS256 — reverting to single-arg turns this red.
    JwtService rs256 =
        new JwtService(props(SECRET, "RS256", JwtTestKeys.PRIVATE_KEY_PATH_4096), REGISTRY);
    String token = rs256.issueAccessToken(42L, Set.of("USER"));
    assertEquals(
        "RS256", headerAlg(token), "RS256 must be pinned regardless of key size (not RS512)");
  }

  @Test
  void issuedRs256Token_acceptsLowercaseSigningAlg_normalizesToRs256() throws Exception {
    // JwtService normalizes signing-alg via trim + toUpperCase (JwtService:89-92), but no test
    // exercised anything but exact case. A lowercase "rs256" must still select the RS256 signer
    // and stamp the pinned kid — otherwise the normalization is fail-closed but unpinned.
    JwtService rs256 =
        new JwtService(props(SECRET, "rs256", JwtTestKeys.PRIVATE_KEY_PATH_A), REGISTRY);
    JsonNode header = decodeHeader(rs256.issueAccessToken(42L, Set.of("USER")));
    assertEquals(
        "RS256",
        header.get("alg").asText(),
        "lowercase signing-alg 'rs256' must normalize to the RS256 signer");
    assertNotNull(header.get("kid"), "normalized RS256 token header must carry a kid key");
    assertEquals(
        SIGNING_KID,
        header.get("kid").asText(),
        "normalized RS256 token still carries the pinned kid");
  }

  @Test
  void issuedRs256Token_acceptsPaddedSigningAlg_normalizesToRs256() throws Exception {
    // The other half of the trim+uppercase normalization: surrounding whitespace must be trimmed
    // so a padded " RS256 " selects the RS256 signer, not the fail-fast branch.
    JwtService rs256 =
        new JwtService(props(SECRET, " RS256 ", JwtTestKeys.PRIVATE_KEY_PATH_A), REGISTRY);
    JsonNode header = decodeHeader(rs256.issueAccessToken(42L, Set.of("USER")));
    assertEquals(
        "RS256",
        header.get("alg").asText(),
        "padded signing-alg ' RS256 ' must trim+normalize to the RS256 signer");
    assertNotNull(header.get("kid"), "normalized RS256 token header must carry a kid key");
    assertEquals(
        SIGNING_KID,
        header.get("kid").asText(),
        "normalized RS256 token still carries the pinned kid");
  }

  @Test
  void issuedRs256Token_containsExactlyContractClaims_verifiedWithPublicKey() {
    // Claim parity is unchanged by the flip: exactly {iss,sub,roles,iat,exp}, iss = configured
    // issuer, verified against the RSA PUBLIC key (the real validation material).
    JwtService rs256 =
        new JwtService(props(SECRET, "RS256", JwtTestKeys.PRIVATE_KEY_PATH_A), REGISTRY);
    String token = rs256.issueAccessToken(42L, Set.of("USER"));

    Claims claims =
        Jwts.parser()
            .verifyWith(JwtTestKeys.KEY_PAIR_A.getPublic())
            .build()
            .parseSignedClaims(token)
            .getPayload();

    assertEquals(Set.of("iss", "sub", "roles", "iat", "exp"), claims.keySet());
    assertEquals("user-service-test", claims.get("iss"), "iss must equal the configured issuer");
    assertEquals("42", claims.getSubject());
    assertEquals(List.of("USER"), claims.get("roles"));
    assertTrue(claims.getIssuedAt() != null);
    assertTrue(claims.getExpiration() != null);
  }

  @Test
  void rs256OnlyWithSecretAbsent_constructsAndIssuesRs256() throws Exception {
    // The exact phase-3 production shape: signer RS256, allowlist RS256-only, and NO legacy secret
    // (the yml `secret:` line is deleted → JwtProperties.secret binds null). The service must
    // construct (hmacKey null, never demanded) and still issue a valid RS256 token — untested
    // today.
    JwtProperties props =
        new JwtProperties(
            null,
            900,
            604800,
            "user-service-test",
            "RS256",
            List.of("RS256"),
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A));
    JwtService service = new JwtService(props, new SimpleMeterRegistry());

    JsonNode header = decodeHeader(service.issueAccessToken(42L, Set.of("USER")));
    assertEquals("RS256", header.get("alg").asText(), "RS256-only service must sign RS256");
    assertEquals(
        SIGNING_KID, header.get("kid").asText(), "RS256-only service still stamps the pinned kid");
  }

  @Test
  void nullSigningAlg_defaultsToRs256_notHs256() throws Exception {
    // The in-code fallback for a null signing-alg must match the yml default (RS256), not the old
    // HS256. With HS256 and RS256 both accepted a null signing-alg constructs either way, so this
    // pins the emitted header: RS256 (new default), never HS256 (the removed inconsistency).
    JwtProperties props =
        new JwtProperties(
            SECRET,
            900,
            604800,
            "user-service-test",
            null,
            List.of("HS256", "RS256"),
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A));
    JwtService service = new JwtService(props, new SimpleMeterRegistry());

    JsonNode header = decodeHeader(service.issueAccessToken(42L, Set.of("USER")));
    assertEquals(
        "RS256", header.get("alg").asText(), "null signing-alg must default to RS256, not HS256");
    assertEquals(
        SIGNING_KID, header.get("kid").asText(), "null-default RS256 token still carries the kid");
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
