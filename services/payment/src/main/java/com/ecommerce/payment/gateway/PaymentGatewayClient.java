package com.ecommerce.payment.gateway;

/** Port for the payment gateway. The sandbox adapter is the only implementation in Wave 3. */
public interface PaymentGatewayClient {

  /**
   * Attempt to charge the given token. The result is one of: approved, declined (a predictable
   * business outcome), or a gateway error (transient, retryable).
   */
  GatewayChargeResult charge(GatewayChargeRequest request);
}
