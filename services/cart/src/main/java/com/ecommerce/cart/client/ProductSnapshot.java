package com.ecommerce.cart.client;

import java.math.BigDecimal;

/** Immutable price/name snapshot captured from the Product Service at add/update time. */
public record ProductSnapshot(
    long productId, String productName, BigDecimal unitPrice, String currency) {}
