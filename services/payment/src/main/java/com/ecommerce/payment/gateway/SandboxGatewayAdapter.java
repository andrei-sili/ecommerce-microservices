package com.ecommerce.payment.gateway;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic sandbox gateway (no real network call). Token keyword rules:
 *
 * <ul>
 *   <li>"decline" anywhere in the token → declined (CARD_DECLINED).
 *   <li>"error" anywhere in the token → transient gateway error (retryable).
 *   <li>Anything else → approved.
 * </ul>
 *
 * A real Stripe/PayPal adapter swaps in behind this same port without changing callers.
 */
@Component
public class SandboxGatewayAdapter implements PaymentGatewayClient {

  @Override
  public GatewayChargeResult charge(GatewayChargeRequest request) {
    String token = request.paymentMethodToken().toLowerCase();
    if (token.contains("decline")) {
      return GatewayChargeResult.declined("CARD_DECLINED");
    }
    if (token.contains("error")) {
      return GatewayChargeResult.error("GATEWAY_UNAVAILABLE");
    }
    // Approved: generate a deterministic gateway payment id.
    String gatewayPaymentId = "gw_" + UUID.randomUUID();
    return GatewayChargeResult.approved(gatewayPaymentId);
  }
}
