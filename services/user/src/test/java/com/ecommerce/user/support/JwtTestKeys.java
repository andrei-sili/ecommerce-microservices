package com.ecommerce.user.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Runtime-generated RSA key material for tests — no PEM is ever committed (repo is public + the
 * committed-secret guard). Two "good" keypairs back the two configured kids (rotation pre-proof); a
 * third backs wrong-keypair tests. Also mints every token variant the dual-accept matrix needs.
 */
public final class JwtTestKeys {

  /** Test-only fixture secret; mirrors {@code security.jwt.secret} in test application.yml. */
  public static final String SECRET =
      "test-jwt-secret-that-is-at-least-32-bytes-long-0123456789"; // gitleaks:allow

  public static final String KID_A = "user-rs256-2026-07";
  public static final String KID_B = "user-rs256-2026-99";

  public static final KeyPair KEY_PAIR_A = generate(2048);
  public static final KeyPair KEY_PAIR_B = generate(2048);
  public static final KeyPair KEY_PAIR_WRONG = generate(2048);

  /**
   * A 4096-bit signing key for the inference-drift pin: single-arg {@code signWith} would infer
   * RS512 on this key, so an explicit {@code Jwts.SIG.RS256} is the only way the emitted header
   * stays {@code RS256}.
   */
  public static final KeyPair KEY_PAIR_4096 = generate(4096);

  public static final String PRIVATE_KEY_PATH_4096 =
      writeTempKey(
          "jwt-priv-4096", pemEncode("PRIVATE KEY", KEY_PAIR_4096.getPrivate().getEncoded()));

  /** SPKI DER bytes of key A's public key — one algorithm-confusion HMAC-key encoding. */
  public static final byte[] PUBLIC_KEY_DER_A = KEY_PAIR_A.getPublic().getEncoded();

  /**
   * X.509/SPKI PEM text of key A's public key — the other algorithm-confusion HMAC-key encoding.
   */
  public static final String PUBLIC_KEY_PEM_A = pemEncode("PUBLIC KEY", PUBLIC_KEY_DER_A);

  public static final String PRIVATE_KEY_PATH_A =
      writeTempKey("jwt-priv-a", pemEncode("PRIVATE KEY", KEY_PAIR_A.getPrivate().getEncoded()));
  public static final String PUBLIC_KEY_PATH_A = writeTempKey("jwt-pub-a", PUBLIC_KEY_PEM_A);
  public static final String PUBLIC_KEY_PATH_B =
      writeTempKey("jwt-pub-b", pemEncode("PUBLIC KEY", KEY_PAIR_B.getPublic().getEncoded()));

  private JwtTestKeys() {}

  // --- Token minting --------------------------------------------------------

  public static String mintRs256(long userId, String kid, KeyPair keyPair) {
    return mintRs256(userId, kid, keyPair, 900);
  }

  public static String mintRs256(long userId, String kid, KeyPair keyPair, long ttlSeconds) {
    Instant now = Instant.now();
    return Jwts.builder()
        .header()
        .keyId(kid)
        .and()
        .issuer("user-service-test")
        .subject(String.valueOf(userId))
        .claim("roles", List.of("USER"))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSeconds)))
        .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
        .compact();
  }

  /** Valid signed RS256 token carrying a caller-supplied roles claim (e.g. a list with a null). */
  public static String mintRs256WithRoles(long userId, String kid, KeyPair keyPair, List<?> roles) {
    Instant now = Instant.now();
    return Jwts.builder()
        .header()
        .keyId(kid)
        .and()
        .issuer("user-service-test")
        .subject(String.valueOf(userId))
        .claim("roles", roles)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(900)))
        .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
        .compact();
  }

  /**
   * Valid RS256 signature but with NO kid header — must be rejected, never an immutable-map NPE.
   */
  public static String mintRs256NoKid(long userId, KeyPair keyPair) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer("user-service-test")
        .subject(String.valueOf(userId))
        .claim("roles", List.of("USER"))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(900)))
        .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
        .compact();
  }

  /** RS384 — a valid RSA signature whose alg is outside the {@code RS256,HS256} allowlist. */
  public static String mintRs384(long userId, String kid, KeyPair keyPair) {
    Instant now = Instant.now();
    return Jwts.builder()
        .header()
        .keyId(kid)
        .and()
        .subject(String.valueOf(userId))
        .claim("roles", List.of("USER"))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(900)))
        .signWith(keyPair.getPrivate(), Jwts.SIG.RS384)
        .compact();
  }

  public static String mintHs256(long userId) {
    return mintHs256(userId, 900);
  }

  public static String mintHs256(long userId, long ttlSeconds) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer("user-service-test")
        .subject(String.valueOf(userId))
        .claim("roles", List.of("USER"))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSeconds)))
        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
        .compact();
  }

  /** Algorithm-confusion forgery: HS256 token HMAC-signed with the trusted public-key bytes. */
  public static String mintAlgConfusion(long userId, byte[] hmacKeyBytes) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("roles", List.of("ADMIN"))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(900)))
        .signWith(Keys.hmacShaKeyFor(hmacKeyBytes), Jwts.SIG.HS256)
        .compact();
  }

  /** Unsecured JWT ({@code alg=none}, empty signature), hand-crafted to bypass the jjwt builder. */
  public static String mintNone(long userId) {
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    String header =
        enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        enc.encodeToString(
            ("{\"sub\":\"" + userId + "\",\"roles\":[\"ADMIN\"]}")
                .getBytes(StandardCharsets.UTF_8));
    return header + "." + payload + ".";
  }

  /**
   * Flips one char in the MIDDLE of the signature segment (#23 lesson: a trailing base64url char
   * has "don't care" low bits, so a last-char flip can decode to the same bytes and still verify).
   */
  public static String tamperSignature(String token) {
    int sigStart = token.lastIndexOf('.') + 1;
    int idx = (sigStart + token.length()) / 2;
    char flipped = token.charAt(idx) == 'A' ? 'B' : 'A';
    return token.substring(0, idx) + flipped + token.substring(idx + 1);
  }

  // --- Fail-fast key files --------------------------------------------------

  /** A path that does not exist on disk. */
  public static String missingKeyPath() {
    return Path.of(
            System.getProperty("java.io.tmpdir"), "jwt-missing-" + System.nanoTime() + ".pem")
        .toString();
  }

  /** PKCS#8 body under a PKCS#1 armor header — the loader must reject on the header. */
  public static String pkcs1HeaderKeyPath() {
    String pkcs8 = pemEncode("PRIVATE KEY", KEY_PAIR_A.getPrivate().getEncoded());
    String pkcs1Armor =
        pkcs8
            // PKCS#1 armor for a fail-fast test — literal markers, no key material (gitleaks FP).
            .replace(
                "-----BEGIN PRIVATE KEY-----", "-----BEGIN RSA PRIVATE KEY-----") // gitleaks:allow
            .replace("-----END PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----");
    return writeTempKey("jwt-pkcs1", pkcs1Armor);
  }

  public static String garbageKeyPath() {
    return writeTempKey("jwt-garbage", "this is not a pem file at all\n");
  }

  /** Valid PKCS#8 PEM with real newlines replaced by literal two-char {@code \\n} sequences. */
  public static String mangledKeyPath() {
    String pem = pemEncode("PRIVATE KEY", KEY_PAIR_A.getPrivate().getEncoded());
    return writeTempKey("jwt-mangled", pem.replace("\n", "\\n"));
  }

  /** A syntactically valid PKCS#8 key that is too weak (1024-bit modulus). */
  public static String weak1024KeyPath() {
    return writeTempKey(
        "jwt-weak", pemEncode("PRIVATE KEY", generate(1024).getPrivate().getEncoded()));
  }

  /** A syntactically valid X.509/SPKI public key that is too weak (1024-bit modulus). */
  public static String weak1024PublicKeyPath() {
    return writeTempKey(
        "jwt-weak-pub", pemEncode("PUBLIC KEY", generate(1024).getPublic().getEncoded()));
  }

  /** X.509 public key under a private-key armor header — public-key fail-fast on the header. */
  public static String wrongHeaderPublicKeyPath() {
    String spki = pemEncode("PUBLIC KEY", KEY_PAIR_A.getPublic().getEncoded());
    return writeTempKey("jwt-pub-wrongheader", spki.replace("PUBLIC KEY", "PRIVATE KEY"));
  }

  // --- Helpers --------------------------------------------------------------

  private static KeyPair generate(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to generate RSA test keypair", e);
    }
  }

  private static String pemEncode(String type, byte[] der) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static String writeTempKey(String prefix, String content) {
    try {
      Path file = Files.createTempFile(prefix + "-", ".pem");
      file.toFile().deleteOnExit();
      Files.writeString(file, content);
      return file.toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
