package com.ecommerce.payment.security;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads RSA public keys from PEM files with startup fail-fast. Payment validates inbound tokens but
 * never signs, so only the X.509/SPKI public key is loaded here (no private key). Every failure
 * throws {@link IllegalStateException} whose message names the config path and the expected PEM
 * format — it <strong>never</strong> echoes key material (base64 body / DER bytes). A bad key fails
 * the bean, which fails the context, which stops the container from becoming ready at deploy time.
 */
final class RsaPemKeys {

  private static final int MIN_MODULUS_BITS = 2048;
  private static final String PUBLIC_HEADER = "-----BEGIN PUBLIC KEY-----";

  private RsaPemKeys() {}

  /** Reads an X.509/SPKI RSA public key associated with {@code kid}. */
  static RSAPublicKey loadPublicKey(String kid, String path) {
    String pem = read(kid, path);
    if (!pem.contains(PUBLIC_HEADER)) {
      throw new IllegalStateException(
          "JWT public key '"
              + kid
              + "' at "
              + path
              + " must be an X.509/SPKI PEM (expected header '"
              + PUBLIC_HEADER
              + "')");
    }
    byte[] der = decode(pem, kid, path);
    try {
      RSAPublicKey key =
          (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      requireStrongModulus(key.getModulus(), kid, path);
      return key;
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException(
          "JWT public key '" + kid + "' at " + path + " is not a valid X.509 RSA key", e);
    }
  }

  private static String read(String kid, String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalStateException("JWT public key '" + kid + "' path is not configured");
    }
    try {
      return Files.readString(Path.of(path));
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException(
          "JWT public key '" + kid + "' file could not be read at " + path, e);
    }
  }

  private static byte[] decode(String pem, String kid, String path) {
    String base64 =
        pem.replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
            .replaceAll("-----END [A-Z0-9 ]+-----", "")
            .replaceAll("\\s", "");
    try {
      return Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      // Non-base64 body (garbage, or newlines mangled into literal "\n" by env transport).
      throw new IllegalStateException(
          "JWT public key '" + kid + "' at " + path + " is not a well-formed X.509/SPKI PEM", e);
    }
  }

  private static void requireStrongModulus(BigInteger modulus, String kid, String path) {
    if (modulus.bitLength() < MIN_MODULUS_BITS) {
      throw new IllegalStateException(
          "JWT public key '"
              + kid
              + "' at "
              + path
              + " has a "
              + modulus.bitLength()
              + "-bit modulus; RSA keys must be at least "
              + MIN_MODULUS_BITS
              + " bits");
    }
  }
}
