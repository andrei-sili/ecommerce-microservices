package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.security.JwtService;
import com.ecommerce.user.support.JwtTestKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Startup fail-fast matrix. The bean constructor is the startup gate: a throw there fails the
 * context, so the container never becomes ready. Each case asserts the message NAMES the expected
 * format and NEVER echoes key material (base64 body always starts with {@code MII} for RSA DER).
 */
class JwtFailFastTest {

  private static final List<String> DUAL = List.of("HS256", "RS256");
  private static final Map<String, String> VALID_PUBLIC =
      Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A);

  private static JwtService build(
      String secret, List<String> algs, String privatePath, Map<String, String> publicKeys) {
    JwtProperties props =
        new JwtProperties(
            secret, 900, 604800, "user-service-test", "HS256", algs, privatePath, publicKeys);
    return new JwtService(props, new SimpleMeterRegistry());
  }

  private static String failMessage(
      String secret, List<String> algs, String privatePath, Map<String, String> publicKeys) {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> build(secret, algs, privatePath, publicKeys));
    String message = ex.getMessage();
    assertFalse(
        message.contains("MII"), "fail-fast message must never echo key material: " + message);
    return message;
  }

  @Test
  void missingPrivateKeyFile_failsFast() {
    String message =
        failMessage(JwtTestKeys.SECRET, DUAL, JwtTestKeys.missingKeyPath(), VALID_PUBLIC);
    assertTrue(message.contains("could not be read"), message);
  }

  @Test
  void pkcs1PrivateKeyHeader_failsFast_namingPkcs8() {
    String message =
        failMessage(JwtTestKeys.SECRET, DUAL, JwtTestKeys.pkcs1HeaderKeyPath(), VALID_PUBLIC);
    assertTrue(message.contains("PKCS#8"), message);
  }

  @Test
  void garbagePrivateKey_failsFast() {
    String message =
        failMessage(JwtTestKeys.SECRET, DUAL, JwtTestKeys.garbageKeyPath(), VALID_PUBLIC);
    assertTrue(message.contains("PKCS#8"), message);
  }

  @Test
  void newlineMangledPrivateKey_failsFast() {
    String message =
        failMessage(JwtTestKeys.SECRET, DUAL, JwtTestKeys.mangledKeyPath(), VALID_PUBLIC);
    assertTrue(message.contains("PKCS#8"), message);
  }

  @Test
  void weak1024BitPrivateKey_failsFast_namingModulus() {
    String message =
        failMessage(JwtTestKeys.SECRET, DUAL, JwtTestKeys.weak1024KeyPath(), VALID_PUBLIC);
    assertTrue(message.contains("2048"), message);
  }

  @Test
  void hs256InAllowlistWithoutSecret_failsFast() {
    String message = failMessage("", DUAL, JwtTestKeys.PRIVATE_KEY_PATH_A, VALID_PUBLIC);
    assertTrue(message.contains("JWT_SECRET"), message);
  }

  @Test
  void publicKeyWithWrongHeader_failsFast_namingSpki() {
    String message =
        failMessage(
            JwtTestKeys.SECRET,
            DUAL,
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.wrongHeaderPublicKeyPath()));
    assertTrue(message.contains("PUBLIC KEY"), message);
  }

  @Test
  void rs256InAllowlistWithoutPublicKey_failsFast() {
    String message =
        failMessage(JwtTestKeys.SECRET, DUAL, JwtTestKeys.PRIVATE_KEY_PATH_A, Map.of());
    assertTrue(message.contains("public key"), message);
  }

  @Test
  void weak1024BitPublicKey_failsFast_namingModulus() {
    String message =
        failMessage(
            JwtTestKeys.SECRET,
            DUAL,
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.weak1024PublicKeyPath()));
    assertTrue(message.contains("2048"), message);
  }

  @Test
  void unknownSigningAlg_failsFast_namingSupportedAlgs() {
    JwtProperties props =
        new JwtProperties(
            JwtTestKeys.SECRET,
            900,
            604800,
            "user-service-test",
            "ES256",
            DUAL,
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            VALID_PUBLIC);
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> new JwtService(props, new SimpleMeterRegistry()));
    assertTrue(ex.getMessage().contains("JWT_SIGNING_ALG"), ex.getMessage());
    assertTrue(ex.getMessage().contains("HS256 or RS256"), ex.getMessage());
  }

  @Test
  void signingAlgRs256_withoutPrivateKey_failsFast() {
    // Flipped to RS256, a missing/unreadable private key must stop startup — never a silent
    // fallback
    // to HS256 signing (§Abuse). The key is loaded unconditionally, so the failure is unmistakable.
    JwtProperties props =
        new JwtProperties(
            JwtTestKeys.SECRET,
            900,
            604800,
            "user-service-test",
            "RS256",
            DUAL,
            JwtTestKeys.missingKeyPath(),
            VALID_PUBLIC);
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> new JwtService(props, new SimpleMeterRegistry()));
    assertTrue(ex.getMessage().contains("could not be read"), ex.getMessage());
  }

  @Test
  void signingHs256_acceptRs256Only_failsFast_crossCheck() {
    // Finding 2 (self-DoS): signing HS256 while accepting RS256-only mints tokens the same service
    // then 401s. Fail fast at startup, naming both properties AND both values (never key material).
    JwtProperties props =
        new JwtProperties(
            JwtTestKeys.SECRET,
            900,
            604800,
            "user-service-test",
            "HS256",
            List.of("RS256"),
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            VALID_PUBLIC);
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> new JwtService(props, new SimpleMeterRegistry()));
    String message = ex.getMessage();
    assertTrue(message.contains("JWT_SIGNING_ALG"), message);
    assertTrue(message.contains("JWT_ACCEPTED_ALGS"), message);
    assertTrue(message.contains("HS256"), message);
    assertTrue(message.contains("RS256"), message);
    assertFalse(
        message.contains("MII"), "cross-check message must never echo key material: " + message);
  }

  @Test
  void signingRs256_acceptHs256Only_failsFast_crossCheck() {
    // The reverse self-DoS combo: signing RS256 while accepting HS256-only. The cross-check must
    // fire in this direction too, naming both properties and both values.
    JwtProperties props =
        new JwtProperties(
            JwtTestKeys.SECRET,
            900,
            604800,
            "user-service-test",
            "RS256",
            List.of("HS256"),
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            VALID_PUBLIC);
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> new JwtService(props, new SimpleMeterRegistry()));
    String message = ex.getMessage();
    assertTrue(message.contains("JWT_SIGNING_ALG"), message);
    assertTrue(message.contains("JWT_ACCEPTED_ALGS"), message);
    assertTrue(message.contains("HS256"), message);
    assertTrue(message.contains("RS256"), message);
  }

  @Test
  void hs256AcceptedWhileSigningRs256_withoutSecret_failsFast_namingAllowlistBranch() {
    // Signer is RS256 but HS256 is still in the allowlist (a valid mid-migration state), so the
    // secret is required only to VALIDATE inbound HS256. The reason must name the allowlist branch,
    // not the signer — the corner the old unconditional "the signer still issues HS256" got wrong.
    JwtProperties props =
        new JwtProperties(
            "",
            900,
            604800,
            "user-service-test",
            "RS256",
            DUAL,
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            VALID_PUBLIC);
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> new JwtService(props, new SimpleMeterRegistry()));
    assertTrue(ex.getMessage().contains("JWT_SECRET"), ex.getMessage());
    assertTrue(ex.getMessage().contains("HS256 is in JWT_ACCEPTED_ALGS"), ex.getMessage());
  }

  @Test
  void signHs256_withoutSecret_failsFast_namingSignerBranch() {
    // Signer issues HS256 (the emergency-rollback posture) with HS256 also accepted: the secret is
    // required to SIGN, so the reason names the signer branch — pins the corrected string (the old
    // code said "the signer still issues HS256" unconditionally, even when the signer did not).
    String message = failMessage("", DUAL, JwtTestKeys.PRIVATE_KEY_PATH_A, VALID_PUBLIC);
    assertTrue(message.contains("the signer issues HS256"), message);
  }

  @Test
  void hs256WithoutSecret_message_pointsAtRollbackPath_notEnvDeadEnd() {
    // Item 1: the ≥32-byte failure must guide operators to the REAL rollback — restore the deleted
    // security.jwt.secret binding (git revert) plus a NEW secret — not the dead-end "set an env
    // var". Phase 3 removed the yml binding, so JWT_SECRET alone now resolves to nothing.
    String message = failMessage("", DUAL, JwtTestKeys.PRIVATE_KEY_PATH_A, VALID_PUBLIC);
    assertTrue(message.contains("security.jwt.secret"), message);
    assertTrue(message.contains("git revert"), message);
    assertFalse(message.contains("configure a strong secret via env"), message);
  }

  @Test
  void unknownAcceptedAlg_failsFast_namingOffendingValue() {
    // Item 3: a typo in JWT_ACCEPTED_ALGS must fail fast, not silently contract the allowlist (a
    // fail-closed but invisible login outage). Name the offending value AND the supported set.
    String message =
        failMessage(
            JwtTestKeys.SECRET,
            List.of("RS256", "FOO256"),
            JwtTestKeys.PRIVATE_KEY_PATH_A,
            VALID_PUBLIC);
    assertTrue(message.contains("JWT_ACCEPTED_ALGS"), message);
    assertTrue(message.contains("FOO256"), message);
    assertTrue(message.contains("HS256, RS256"), message);
  }
}
