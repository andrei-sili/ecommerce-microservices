package com.ecommerce.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.order.dto.OrderItemResponse;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.exception.InsufficientStockException;
import com.ecommerce.order.exception.OrderNotCancellableException;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.security.CurrentUserArgumentResolver;
import com.ecommerce.order.security.JwtService;
import com.ecommerce.order.security.RestAccessDeniedHandler;
import com.ecommerce.order.security.RestAuthenticationEntryPoint;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.order.service.OrderService.PlacementResult;
import com.ecommerce.order.support.TestJwt;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import({
  com.ecommerce.order.config.SecurityConfig.class,
  com.ecommerce.order.config.WebConfig.class,
  CurrentUserArgumentResolver.class,
  JwtService.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class,
  com.ecommerce.order.exception.GlobalExceptionHandler.class,
  OrderControllerWebTest.MeterRegistryConfig.class
})
@EnableConfigurationProperties(com.ecommerce.order.config.JwtProperties.class)
// HS256-only here (this slice tests the controller, not the RS256 matrix) so no public key is
// required to boot JwtService; the full dual-accept matrix lives in the security integration tests.
@TestPropertySource(
    properties = {"security.jwt.secret=" + TestJwt.SECRET, "security.jwt.accepted-algs=HS256"})
class OrderControllerWebTest {

  @TestConfiguration
  static class MeterRegistryConfig {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  private static final UUID ORDER_ID = UUID.fromString("9f1c2e7a-0000-0000-0000-000000000001");
  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OrderService orderService;

  private OrderResponse sampleOrder(OrderStatus status) {
    return new OrderResponse(
        ORDER_ID,
        7L,
        status,
        "EUR",
        List.of(
            new OrderItemResponse(
                42L, "Black T-Shirt", new BigDecimal("19.99"), 2, new BigDecimal("39.98"))),
        new BigDecimal("39.98"),
        new BigDecimal("39.98"),
        Instant.parse("2026-06-25T10:00:00Z"),
        Instant.parse("2026-06-25T10:00:00Z"));
  }

  // ---- Auth ----

  @Test
  void place_noToken_returns401() throws Exception {
    mockMvc
        .perform(post("/api/v1/orders").header("Idempotency-Key", "k1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    verifyNoInteractions(orderService);
  }

  @Test
  void place_expiredToken_returns401() throws Exception {
    String expired = TestJwt.bearer(TestJwt.expiredToken("7", List.of("USER")));
    mockMvc
        .perform(
            post("/api/v1/orders").header("Authorization", expired).header("Idempotency-Key", "k1"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(orderService);
  }

  // ---- Placement ----

  @Test
  void place_missingIdempotencyKey_returns400() throws Exception {
    mockMvc
        .perform(post("/api/v1/orders").header("Authorization", USER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REQUIRED"));
    verifyNoInteractions(orderService);
  }

  @Test
  void place_new_returns201WithLocation() throws Exception {
    when(orderService.placeOrder(any(), eq("k1")))
        .thenReturn(new PlacementResult(sampleOrder(OrderStatus.PENDING), false));
    mockMvc
        .perform(
            post("/api/v1/orders").header("Authorization", USER).header("Idempotency-Key", "k1"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/orders/" + ORDER_ID))
        .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.total").value(39.98))
        .andExpect(jsonPath("$.items[0].product_id").value(42));
  }

  @Test
  void place_replayedKey_returns200() throws Exception {
    when(orderService.placeOrder(any(), eq("k1")))
        .thenReturn(new PlacementResult(sampleOrder(OrderStatus.PENDING), true));
    mockMvc
        .perform(
            post("/api/v1/orders").header("Authorization", USER).header("Idempotency-Key", "k1"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
  }

  @Test
  void place_emptyCart_returns422() throws Exception {
    when(orderService.placeOrder(any(), any()))
        .thenThrow(new com.ecommerce.order.exception.EmptyCartException());
    mockMvc
        .perform(
            post("/api/v1/orders").header("Authorization", USER).header("Idempotency-Key", "k1"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("EMPTY_CART"));
  }

  @Test
  void place_insufficientStock_returns409WithProductId() throws Exception {
    when(orderService.placeOrder(any(), any()))
        .thenThrow(new InsufficientStockException(42L, "Insufficient stock for Black T-Shirt"));
    mockMvc
        .perform(
            post("/api/v1/orders").header("Authorization", USER).header("Idempotency-Key", "k1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
        .andExpect(jsonPath("$.product_id").value(42));
  }

  // ---- Detail / ownership ----

  @Test
  void get_notOwner_returns404() throws Exception {
    when(orderService.getOrder(any(), eq(ORDER_ID))).thenThrow(new OrderNotFoundException());
    mockMvc
        .perform(get("/api/v1/orders/" + ORDER_ID).header("Authorization", USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
  }

  // ---- Cancel ----

  @Test
  void cancel_pending_returns200Cancelled() throws Exception {
    when(orderService.cancelOrder(any(), eq(ORDER_ID)))
        .thenReturn(sampleOrder(OrderStatus.CANCELLED));
    mockMvc
        .perform(
            patch("/api/v1/orders/" + ORDER_ID)
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void cancel_notPending_returns409() throws Exception {
    when(orderService.cancelOrder(any(), eq(ORDER_ID)))
        .thenThrow(new OrderNotCancellableException());
    mockMvc
        .perform(
            patch("/api/v1/orders/" + ORDER_ID)
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("ORDER_NOT_CANCELLABLE"));
  }

  @Test
  void patch_unsupportedStatus_returns400() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/orders/" + ORDER_ID)
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PENDING\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("UNSUPPORTED_STATUS_TRANSITION"));
    verifyNoInteractions(orderService);
  }

  @Test
  void patch_unknownStatus_returns400() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/orders/" + ORDER_ID)
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SHIPPED\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(orderService);
  }
}
