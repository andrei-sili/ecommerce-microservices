package com.ecommerce.order.dto;

import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    Long userId,
    OrderStatus status,
    String currency,
    List<OrderItemResponse> items,
    BigDecimal subtotal,
    BigDecimal total,
    Instant createdAt,
    Instant updatedAt) {

  public static OrderResponse from(OrderEntity order) {
    return new OrderResponse(
        order.getId(),
        order.getUserId(),
        order.getStatus(),
        order.getCurrency(),
        order.getItems().stream().map(OrderItemResponse::from).toList(),
        order.getSubtotal(),
        order.getTotal(),
        order.getCreatedAt(),
        order.getUpdatedAt());
  }
}
