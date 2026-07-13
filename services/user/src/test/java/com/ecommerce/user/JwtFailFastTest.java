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
  void signingAlgRs256_inPhase1_failsFast() {
    JwtProperties props =
        new JwtProperties(
            JwtTestKeys.SECRET,
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
    assertTrue(ex.getMessage().contains("JWT_SIGNING_ALG"), ex.getMessage());
    assertTrue(ex.getMessage().contains("phase 2"), ex.getMessage());
  }
}
