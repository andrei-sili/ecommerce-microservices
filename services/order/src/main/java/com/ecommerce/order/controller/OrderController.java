package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.dto.PageResponse;
import com.ecommerce.order.dto.UpdateOrderRequest;
import com.ecommerce.order.exception.BadRequestException;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.security.CurrentUser;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.order.service.OrderService.PlacementResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

  private static final int MAX_PAGE_SIZE = 100;

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  /**
   * Place an order from the caller's server-side cart. {@code Idempotency-Key} is required (a
   * missing header is rejected 400 by {@code MissingRequestHeaderException}); a replayed key
   * returns the original order with 200 instead of 201.
   */
  @PostMapping
  public ResponseEntity<OrderResponse> place(
      CurrentUser user, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new BadRequestException(
          "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
    }
    PlacementResult result = orderService.placeOrder(user, idempotencyKey);
    if (result.replayed()) {
      return ResponseEntity.ok(result.order());
    }
    return ResponseEntity.created(URI.create("/api/v1/orders/" + result.order().id()))
        .body(result.order());
  }

  @GetMapping
  public PageResponse<OrderResponse> list(
      CurrentUser user,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return orderService.listOrders(user, page, size);
  }

  @GetMapping("/{orderId}")
  public OrderResponse get(CurrentUser user, @PathVariable UUID orderId) {
    return orderService.getOrder(user, orderId);
  }

  @PatchMapping("/{orderId}")
  public OrderResponse update(
      CurrentUser user,
      @PathVariable UUID orderId,
      @Valid @RequestBody UpdateOrderRequest request) {
    if (request.status() != OrderStatus.CANCELLED) {
      throw new BadRequestException(
          "UNSUPPORTED_STATUS_TRANSITION", "Only cancellation is supported");
    }
    return orderService.cancelOrder(user, orderId);
  }
}
