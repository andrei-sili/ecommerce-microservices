package com.ecommerce.payment.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Subset of the Order Service response used by Payment. Fields are snake_case in the JSON wire
 * format (Boot's SNAKE_CASE ObjectMapper maps order_id → orderId, user_id → userId, etc.).
 */
public record OrderView(UUID orderId, Long userId, String status, BigDecimal total, String currency) {}
