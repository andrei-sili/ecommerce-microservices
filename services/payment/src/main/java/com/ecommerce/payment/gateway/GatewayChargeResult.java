package com.ecommerce.payment.gateway;

/** Outcome of a gateway charge attempt. */
public record GatewayChargeResult(
    boolean approved,
    boolean gatewayError,
    String gatewayPaymentId,
    String failureReason) {

  public static GatewayChargeResult approved(String gatewayPaymentId) {
    return new GatewayChargeResult(true, false, gatewayPaymentId, null);
  }

  public static GatewayChargeResult declined(String failureReason) {
    return new GatewayChargeResult(false, false, null, failureReason);
  }

  public static GatewayChargeResult error(String reason) {
    return new GatewayChargeResult(false, true, null, reason);
  }
}
