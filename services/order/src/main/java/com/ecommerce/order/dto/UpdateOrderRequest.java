package com.ecommerce.order.dto;

import com.ecommerce.order.model.OrderStatus;
import jakarta.validation.constraints.NotNull;

/** State transition request. Wave 2 supports only the cancel transition. */
public record UpdateOrderRequest(@NotNull OrderStatus status) {}
