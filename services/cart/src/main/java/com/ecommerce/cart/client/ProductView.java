package com.ecommerce.cart.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Minimal projection of the Product Service response — only the fields the cart snapshots. Unknown
 * fields (category, sku, timestamps, ...) are ignored so the contract can evolve without breaking.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(
    Long id, String name, BigDecimal price, String currency, Boolean available) {}
