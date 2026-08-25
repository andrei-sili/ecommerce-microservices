package com.ecommerce.cart.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Minimal projection of the Product Service response — only the fields the cart snapshots. Unknown
 * fields (category, sku, timestamps, ...) are ignored so the contract can evolve without breaking.
 *
 * <p>{@code stockQuantity} is the projection's only multi-token field. The cart does not act on it
 * — availability is Product's decision, carried by {@code available} — but it is the one component
 * whose binding distinguishes a snake_case-configured deserializer from Jackson's camelCase
 * default, since every other field is a single token that both conventions spell identically.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductView(
    Long id,
    String name,
    BigDecimal price,
    String currency,
    Boolean available,
    Integer stockQuantity) {}
