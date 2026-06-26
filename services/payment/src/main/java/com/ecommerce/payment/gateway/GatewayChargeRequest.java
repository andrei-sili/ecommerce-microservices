package com.ecommerce.payment.gateway;

import java.util.UUID;

/**
 * Request to the payment gateway. Amount is in minor units (integer cents) to avoid floating-point
 * rounding errors.
 */
public record GatewayChargeRequest(
    UUID paymentId, String paymentMethodToken, long amountMinorUnits, String currency) {}
