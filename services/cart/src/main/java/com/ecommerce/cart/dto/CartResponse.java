package com.ecommerce.cart.dto;

import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartResponse(
    Long userId,
    String currency,
    List<CartItemResponse> items,
    BigDecimal subtotal,
    Integer totalItems,
    Instant updatedAt) {

  public static CartResponse from(Cart cart) {
    List<CartItemResponse> items =
        cart.getItems().stream().map(CartItemResponse::from).toList();
    BigDecimal subtotal =
        cart.getItems().stream()
            .map(CartItem::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    int totalItems = cart.getItems().stream().mapToInt(CartItem::getQuantity).sum();
    return new CartResponse(
        cart.getUserId(),
        cart.currency().orElse(null),
        items,
        subtotal,
        totalItems,
        cart.getUpdatedAt());
  }
}
