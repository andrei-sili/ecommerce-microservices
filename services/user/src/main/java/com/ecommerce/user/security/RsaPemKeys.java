package com.ecommerce.user.security;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads RSA keys from PEM files with startup fail-fast. Every failure throws {@link
 * IllegalStateException} whose message names the config path and the expected PEM format — it
 * <strong>never</strong> echoes key material (base64 body / DER bytes). A bad key therefore fails
 * the bean, which fails the context, which stops the container from becoming ready at deploy time.
 */
final class RsaPemKeys {

  private static final int MIN_MODULUS_BITS = 2048;
  // Format-header literal for PEM validation — no key material (gitleaks false positive).
  private static final String PRIVATE_HEADER = "-----BEGIN PRIVATE KEY-----"; // gitleaks:allow
  private static final String PUBLIC_HEADER = "-----BEGIN PUBLIC KEY-----";

  private RsaPemKeys() {}

  /** Reads an unencrypted PKCS#8 RSA private key. */
  static RSAPrivateKey loadPrivateKey(String path) {
    String pem = read(path, "private");
    if (!pem.contains(PRIVATE_HEADER)) {
      throw new IllegalStateException(
          "JWT private key at "
              + path
              + " must be an unencrypted PKCS#8 PEM (expected header '"
              + PRIVATE_HEADER
              + "'); PKCS#1 ('-----BEGIN RSA PRIVATE KEY-----') is not supported");
    }
    byte[] der = decode(pem, path, "private", "PKCS#8");
    try {
      RSAPrivateKey key =
          (RSAPrivateKey)
              KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      requireStrongModulus(key.getModulus(), path, "private");
      return key;
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException(
          "JWT private key at " + path + " is not a valid PKCS#8 RSA key", e);
    }
  }

  /** Reads an X.509/SPKI RSA public key associated with {@code kid}. */
  static RSAPublicKey loadPublicKey(String kid, String path) {
    String pem = read(path, "public");
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
    byte[] der = decode(pem, path, "public", "X.509/SPKI");
    try {
      RSAPublicKey key =
          (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      requireStrongModulus(key.getModulus(), path, "public");
      return key;
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException(
          "JWT public key '" + kid + "' at " + path + " is not a valid X.509 RSA key", e);
    }
  }

  private static String read(String path, String kind) {
    if (path == null || path.isBlank()) {
      throw new IllegalStateException("JWT " + kind + " key path is not configured");
    }
    try {
      return Files.readString(Path.of(path));
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("JWT " + kind + " key file could not be read at " + path, e);
    }
  }

  private static byte[] decode(String pem, String path, String kind, String format) {
    String base64 =
        pem.replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
            .replaceAll("-----END [A-Z0-9 ]+-----", "")
            .replaceAll("\\s", "");
    try {
      return Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      // Non-base64 body (garbage, or newlines mangled into literal "\n" by env transport).
      throw new IllegalStateException(
          "JWT " + kind + " key at " + path + " is not a well-formed " + format + " PEM", e);
    }
  }

  private static void requireStrongModulus(BigInteger modulus, String path, String kind) {
    if (modulus.bitLength() < MIN_MODULUS_BITS) {
      throw new IllegalStateException(
          "JWT "
              + kind
              + " key at "
              + path
              + " has a "
              + modulus.bitLength()
              + "-bit modulus; RSA keys must be at least "
              + MIN_MODULUS_BITS
              + " bits");
    }
  }
}
