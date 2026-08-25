package com.ecommerce.cart.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.config.JwtProperties;
import com.ecommerce.cart.config.SecurityConfig;
import com.ecommerce.cart.dto.CartItemResponse;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.exception.ConflictException;
import com.ecommerce.cart.exception.GlobalExceptionHandler;
import com.ecommerce.cart.exception.NotFoundException;
import com.ecommerce.cart.exception.UnprocessableEntityException;
import com.ecommerce.cart.security.JwtService;
import com.ecommerce.cart.security.RestAccessDeniedHandler;
import com.ecommerce.cart.security.RestAuthenticationEntryPoint;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.cart.support.TestJwt;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@WebMvcTest(CartController.class)
@Import({
  SecurityConfig.class,
  JwtService.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class,
  GlobalExceptionHandler.class,
  CartControllerWebTest.TestBeans.class
})
@EnableConfigurationProperties(JwtProperties.class)
// Slice-test scope: exercise only the legacy HS256 branch (mints HS256 tokens), so no RS256 public
// key is required. The full dual-accept matrix runs through the real filter chain in the
// full-context DualAccept suites.
@TestPropertySource(
    properties = {"security.jwt.secret=" + TestJwt.SECRET, "security.jwt.accepted-algs=HS256"})
class CartControllerWebTest {

  @TestConfiguration
  static class TestBeans {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Autowired private MockMvc mockMvc;
  @MockitoBean private CartService cartService;

  private static final String USER_7 = TestJwt.bearer(TestJwt.token("7", List.of("USER")));
  private static final String USER_8 = TestJwt.bearer(TestJwt.token("8", List.of("USER")));

  private CartResponse sampleCart(long userId) {
    CartItemResponse item =
        new CartItemResponse(
            42L,
            "Black T-Shirt",
            new BigDecimal("19.99"),
            2,
            new BigDecimal("39.98"),
            Instant.parse("2026-06-25T10:30:00Z"));
    return new CartResponse(
        userId,
        "EUR",
        List.of(item),
        new BigDecimal("39.98"),
        2,
        Instant.parse("2026-06-25T10:30:00Z"));
  }

  // ---- Authentication required on every endpoint ----

  @Test
  void getCart_noToken_returns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/cart"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    verifyNoInteractions(cartService);
  }

  @Test
  void addItem_expiredToken_returns401() throws Exception {
    String expired = TestJwt.bearer(TestJwt.expiredToken("7", List.of("USER")));
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":2}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(cartService);
  }

  // ---- Happy paths ----

  @Test
  void getCart_returnsCartObjectInSnakeCase() throws Exception {
    when(cartService.getCart(7L)).thenReturn(sampleCart(7L));
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value(7))
        .andExpect(jsonPath("$.currency").value("EUR"))
        .andExpect(jsonPath("$.subtotal").value(39.98))
        .andExpect(jsonPath("$.total_items").value(2))
        .andExpect(jsonPath("$.items[0].product_id").value(42))
        .andExpect(jsonPath("$.items[0].unit_price").value(19.99))
        .andExpect(jsonPath("$.items[0].line_total").value(39.98))
        .andExpect(jsonPath("$.items[0].snapshot_at").exists());
  }

  @Test
  void addItem_returns201WithCart() throws Exception {
    when(cartService.addItem(eq(7L), eq(42L), eq(2), anyString())).thenReturn(sampleCart(7L));
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.items[0].product_id").value(42));
  }

  @Test
  void setQuantity_returns200WithCart() throws Exception {
    when(cartService.setItemQuantity(eq(7L), eq(42L), eq(5), anyString()))
        .thenReturn(sampleCart(7L));
    mockMvc
        .perform(
            put("/api/v1/cart/items/42")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value(7));
  }

  @Test
  void removeItem_returns204AndIsIdempotent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/cart/items/42").header("Authorization", USER_7))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/v1/cart/items/42").header("Authorization", USER_7))
        .andExpect(status().isNoContent());
  }

  @Test
  void clearCart_returns204() throws Exception {
    mockMvc
        .perform(delete("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isNoContent());
    verify(cartService).clearCart(7L);
  }

  // ---- Error mappings from the service ----
  //
  // These rows render through GlobalExceptionHandler.handleApi (ApiException), not through
  // handleExceptionInternal. The substitution probe used to attribute the validation rows below —
  // handleExceptionInternal rendering ProblemDetail.forStatus instead of the envelope — cannot
  // reach handleApi, so it never exercised these rows. Their media type is therefore unverified in
  // either direction: not shown guarded, not shown unguarded. Settling it needs a mutation on
  // handleApi itself, with the same paired counterfactual (red on the mutated rendering, green on
  // the shipped one).

  @Test
  void addItem_unavailableProduct_returns409() throws Exception {
    when(cartService.addItem(eq(7L), eq(42L), eq(2), anyString()))
        .thenThrow(new ConflictException("PRODUCT_UNAVAILABLE", "unavailable"));
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("PRODUCT_UNAVAILABLE"));
  }

  @Test
  void addItem_missingProduct_returns404() throws Exception {
    when(cartService.addItem(eq(7L), eq(99L), eq(1), anyString()))
        .thenThrow(new NotFoundException("PRODUCT_NOT_FOUND", "not found"));
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":99,\"quantity\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void addItem_mixedCurrency_returns422() throws Exception {
    when(cartService.addItem(eq(7L), eq(50L), eq(1), anyString()))
        .thenThrow(new UnprocessableEntityException("MIXED_CURRENCY_CART", "mixed"));
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":50,\"quantity\":1}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("MIXED_CURRENCY_CART"));
  }

  @Test
  void addItem_quantityLimitExceeded_returns422() throws Exception {
    when(cartService.addItem(eq(7L), eq(42L), eq(50), anyString()))
        .thenThrow(new UnprocessableEntityException("QUANTITY_LIMIT_EXCEEDED", "too many"));
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":50}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("QUANTITY_LIMIT_EXCEEDED"));
  }

  @Test
  void setQuantity_itemNotInCart_returns404() throws Exception {
    when(cartService.setItemQuantity(eq(7L), eq(42L), eq(3), anyString()))
        .thenThrow(new NotFoundException("CART_ITEM_NOT_FOUND", "absent"));
    mockMvc
        .perform(
            put("/api/v1/cart/items/42")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":3}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("CART_ITEM_NOT_FOUND"));
  }

  // ---- Validation ----
  //
  // These three rows render through GlobalExceptionHandler.handleMethodArgumentNotValid ->
  // handleExceptionInternal, the same single body-rewrite point as the framework-error rows. Per
  // §4h(3) the exact media-type equality runs BEFORE the body assertion: every way this path drifts
  // (a ProblemDetail body, a converter that cannot write the envelope) also destroys the body, so a
  // leading jsonPath reports `No value at JSON path "$.error"` and points the reader at the wrong
  // cause. Measured with the sanctioned substitution probe (handleExceptionInternal renders
  // ProblemDetail.forStatus): with the jsonPath first all three said "No value at JSON path"; with
  // the equality first they name application/problem+json.

  @Test
  void addItem_invalidQuantity_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    verifyNoInteractions(cartService);
  }

  @Test
  void addItem_quantityOver100_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":101}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    verifyNoInteractions(cartService);
  }

  @Test
  void setQuantity_zero_returns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/cart/items/42")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    verifyNoInteractions(cartService);
  }

  // ---- Ownership: the user id passed to the service comes from the token `sub`, not the client
  // ----

  @Test
  void getCart_ownershipDerivedFromTokenSubject() throws Exception {
    when(cartService.getCart(8L)).thenReturn(sampleCart(8L));
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_8))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value(8));
    verify(cartService).getCart(8L);
    // Confirm the other user's cart was never loaded for this token.
    verify(cartService, org.mockito.Mockito.never()).getCart(7L);
  }
}
