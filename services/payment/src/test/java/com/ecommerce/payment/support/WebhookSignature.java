package com.ecommerce.payment.support;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;

/**
 * Computes the {@code X-Webhook-Signature} the service verifies (HMAC-SHA256 over the raw body).
 */
public final class WebhookSignature {

  private WebhookSignature() {}

  public static String of(String body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Hex.encodeHexString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 not available", e);
    }
  }
}
