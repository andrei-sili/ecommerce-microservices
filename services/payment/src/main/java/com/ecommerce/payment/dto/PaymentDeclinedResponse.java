package com.ecommerce.payment.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Error envelope returned on 402 PAYMENT_REQUIRED (declined charge). Extends the standard error
 * envelope with {@code payment_id} (so the client can look up the persisted FAILED payment) and
 * {@code failure_reason} (machine code, e.g. {@code CARD_DECLINED}) for programmatic handling.
 *
 * <p>Serialized with SNAKE_CASE via Boot's ObjectMapper: {@code paymentId} → {@code payment_id},
 * {@code failureReason} → {@code failure_reason}.
 */
public class PaymentDeclinedResponse {

  private String error;
  private String message;
  private Instant timestamp;
  private String path;
  private UUID paymentId;
  private String failureReason;

  private PaymentDeclinedResponse() {}

  public static PaymentDeclinedResponse of(
      String path, UUID paymentId, String failureReason) {
    PaymentDeclinedResponse r = new PaymentDeclinedResponse();
    r.error = "PAYMENT_DECLINED";
    r.message = "Payment was declined: " + failureReason;
    r.timestamp = Instant.now();
    r.path = path;
    r.paymentId = paymentId;
    r.failureReason = failureReason;
    return r;
  }

  public String getError() {
    return error;
  }

  public String getMessage() {
    return message;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public String getPath() {
    return path;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public String getFailureReason() {
    return failureReason;
  }
}
