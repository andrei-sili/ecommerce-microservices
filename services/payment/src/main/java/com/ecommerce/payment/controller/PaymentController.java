package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.CreatePaymentRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.exception.ApiException;
import com.ecommerce.payment.security.CurrentUser;
import com.ecommerce.payment.service.PaymentService;
import com.ecommerce.payment.service.PaymentService.CreateResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  /**
   * Initiate payment for an order. The {@code Idempotency-Key} header is required: a replayed key
   * returns the original outcome verbatim (same HTTP status + body), never charging twice.
   */
  @PostMapping
  public ResponseEntity<PaymentResponse> createPayment(
      CurrentUser caller,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreatePaymentRequest request) {

    CreateResult result = paymentService.createPayment(caller, idempotencyKey, request);

    // A declined payment is persisted FAILED and returned 402 (predictable business outcome).
    if (!result.replayed() && "FAILED".equals(result.response().getStatus())) {
      return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(result.response());
    }

    // Replayed key: return the original outcome (could be 201-originally or 402-originally).
    if (result.replayed()) {
      if ("FAILED".equals(result.response().getStatus())) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(result.response());
      }
      return ResponseEntity.ok(result.response());
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
  }

  /** Payment status. ADMIN may read any; a user reads only their own (404 on cross-user). */
  @GetMapping("/{paymentId}")
  public ResponseEntity<PaymentResponse> getPayment(
      CurrentUser caller, @PathVariable UUID paymentId) {
    return ResponseEntity.ok(paymentService.getPayment(caller, paymentId));
  }

  /**
   * Gateway webhook callback. Authenticated by HMAC-SHA256 signature over the raw body — no JWT.
   * The raw body is passed through so the service can verify the signature before parsing.
   */
  @PostMapping("/webhook")
  public ResponseEntity<Void> handleWebhook(
      @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
      @RequestBody byte[] rawBody) {

    if (rawBody == null || rawBody.length == 0) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "MALFORMED_WEBHOOK", "Empty webhook body");
    }
    paymentService.processWebhook(rawBody, signature);
    return ResponseEntity.ok().build();
  }
}
