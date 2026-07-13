package com.ecommerce.order.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecommerce.order.config.JwtProperties;
import com.ecommerce.order.support.JwtTestKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Startup fail-fast matrix against the PUBLIC key (Order is a validator only — no private key). The
 * bean constructor is the startup gate: a throw there fails the context, so the container never
 * becomes ready. Each case asserts the message NAMES the expected format and NEVER echoes key
 * material (base64 body always starts with {@code MII} for RSA DER).
 */
class JwtFailFastTest {

  private static final List<String> DUAL = List.of("HS256", "RS256");
  private static final Map<String, String> VALID_PUBLIC =
      Map.of(JwtTestKeys.KID_A, JwtTestKeys.PUBLIC_KEY_PATH_A);

  private static JwtService build(
      String secret, List<String> algs, Map<String, String> publicKeys) {
    return new JwtService(new JwtProperties(secret, algs, publicKeys), new SimpleMeterRegistry());
  }

  private static String failMessage(
      String secret, List<String> algs, Map<String, String> publicKeys) {
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> build(secret, algs, publicKeys));
    String message = ex.getMessage();
    assertFalse(
        message.contains("MII"), "fail-fast message must never echo key material: " + message);
    return message;
  }

  @Test
  void missingPublicKeyFile_failsFast() {
    String message =
        failMessage(
            JwtTestKeys.SECRET, DUAL, Map.of(JwtTestKeys.KID_A, JwtTestKeys.missingKeyPath()));
    assertTrue(message.contains("could not be read"), message);
  }

  @Test
  void wrongHeaderPublicKey_failsFast_namingSpki() {
    String message =
        failMessage(
            JwtTestKeys.SECRET,
            DUAL,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.wrongHeaderPublicKeyPath()));
    assertTrue(message.contains("PUBLIC KEY"), message);
  }

  @Test
  void garbagePublicKey_failsFast() {
    String message =
        failMessage(
            JwtTestKeys.SECRET,
            DUAL,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.garbagePublicKeyPath()));
    assertTrue(message.contains("X.509/SPKI"), message);
  }

  @Test
  void newlineMangledPublicKey_failsFast() {
    String message =
        failMessage(
            JwtTestKeys.SECRET,
            DUAL,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.mangledPublicKeyPath()));
    assertTrue(message.contains("X.509/SPKI"), message);
  }

  @Test
  void weak1024BitPublicKey_failsFast_namingModulus() {
    String message =
        failMessage(
            JwtTestKeys.SECRET,
            DUAL,
            Map.of(JwtTestKeys.KID_A, JwtTestKeys.weak1024PublicKeyPath()));
    assertTrue(message.contains("2048"), message);
  }

  @Test
  void hs256InAllowlistWithoutSecret_failsFast() {
    String message = failMessage("", DUAL, VALID_PUBLIC);
    assertTrue(message.contains("JWT_SECRET"), message);
  }

  @Test
  void rs256InAllowlistWithoutPublicKey_failsFast() {
    String message = failMessage(JwtTestKeys.SECRET, DUAL, Map.of());
    assertTrue(message.contains("public key"), message);
  }

  @Test
  void emptyAcceptedAlgs_failsFast() {
    String message = failMessage(JwtTestKeys.SECRET, List.of(), VALID_PUBLIC);
    assertTrue(message.contains("JWT_ACCEPTED_ALGS"), message);
  }
}
