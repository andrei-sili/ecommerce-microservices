package com.ecommerce.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/** Subset of the Cart Service response Order needs to drive placement. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CartView(Long userId, String currency, List<CartItem> items, BigDecimal subtotal) {

  public boolean isEmpty() {
    return items == null || items.isEmpty();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CartItem(Long productId, int quantity) {}
}
