package com.ecommerce.cart;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.client.ProductClient;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.ecommerce.cart.support.StubProductClient;
import com.ecommerce.cart.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end cart flow against a real Postgres with the Product client stubbed (no real HTTP). */
@Import(CartIntegrationTest.StubConfig.class)
class CartIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void dualAllowlist(DynamicPropertyRegistry registry) {
    // This suite presents legacy HS256 tokens as "a valid token" for the business flow. Production
    // now defaults to RS256-only (main application.yml, Slice 5e phase 3); pin the dual (rollback)
    // allowlist here so the HS256 path stays enabled independent of the shipped default.
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
  }

  @TestConfiguration
  static class StubConfig {
    @Bean
    @Primary
    StubProductClient stubProductClient() {
      return new StubProductClient();
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ProductClient productClient;
  @Autowired private CartRepository cartRepository;

  private StubProductClient stub() {
    return (StubProductClient) productClient;
  }

  private static final String USER_7 = TestJwt.bearer(TestJwt.token("7", List.of("USER")));
  private static final String USER_8 = TestJwt.bearer(TestJwt.token("8", List.of("USER")));

  @BeforeEach
  void setUp() {
    cartRepository.deleteAll();
    stub().reset();
    stub().register(42L, "Black T-Shirt", "19.99", "EUR", true);
    stub().register(43L, "Blue Jeans", "49.50", "EUR", true);
    stub().register(50L, "US Mug", "9.00", "USD", true);
    stub().register(60L, "Out Of Stock", "5.00", "EUR", false);
  }

  @Test
  void emptyCart_isAutoCreated() throws Exception {
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value(7))
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.total_items").value(0));
  }

  @Test
  void addIncrementSetRemoveClear_fullLifecycle() throws Exception {
    // Add 2 of product 42.
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.currency").value("EUR"))
        .andExpect(jsonPath("$.items[0].product_name").value("Black T-Shirt"))
        .andExpect(jsonPath("$.items[0].unit_price").value(19.99))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        .andExpect(jsonPath("$.items[0].line_total").value(39.98))
        .andExpect(jsonPath("$.subtotal").value(39.98))
        .andExpect(jsonPath("$.total_items").value(2));

    // Adding the same product increments quantity; unique(cart_id, product_id) keeps one row.
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":3}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].quantity").value(5))
        .andExpect(jsonPath("$.subtotal").value(99.95));

    // Add a second product.
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":43,\"quantity\":1}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.total_items").value(6));

    // Set product 42 to an absolute quantity of 1 (idempotent PUT). Items keep insertion order
    // (id ASC), so product 42 is index 0 and product 43 is index 1.
    mockMvc
        .perform(
            put("/api/v1/cart/items/42")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].product_id").value(42))
        .andExpect(jsonPath("$.items[0].quantity").value(1));
    // Repeating the same PUT yields the same state.
    mockMvc
        .perform(
            put("/api/v1/cart/items/42")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].product_id").value(42))
        .andExpect(jsonPath("$.items[0].quantity").value(1));

    // Remove product 42, idempotent.
    mockMvc
        .perform(delete("/api/v1/cart/items/42").header("Authorization", USER_7))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/v1/cart/items/42").header("Authorization", USER_7))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].product_id").value(43));

    // Clear the cart, idempotent.
    mockMvc
        .perform(delete("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.currency").doesNotExist());
  }

  // The domain-error rows that follow render through GlobalExceptionHandler.handleApi
  // (ApiException), not through handleExceptionInternal. The substitution probe used to attribute
  // the framework rows — handleExceptionInternal rendering ProblemDetail.forStatus instead of the
  // envelope — cannot reach handleApi, so it never exercised these rows. Their media type is
  // therefore unverified in either direction: not shown guarded, not shown unguarded. Settling it
  // needs a mutation on handleApi itself, with the same paired counterfactual (red on the mutated
  // rendering, green on the shipped one).
  @Test
  void addingDifferentCurrency_returns422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":1}"))
        .andExpect(status().isCreated());
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
  void incrementBeyond100_returns422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":60}"))
        .andExpect(status().isCreated());
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
  void unavailableProduct_returns409() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":60,\"quantity\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("PRODUCT_UNAVAILABLE"));
  }

  @Test
  void missingProduct_returns404() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":9999,\"quantity\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void setQuantity_itemNotInCart_returns404() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/cart/items/42")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("CART_ITEM_NOT_FOUND"));
  }

  @Test
  void cartsAreIsolatedPerUser() throws Exception {
    // User 7 adds product 42.
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":2}"))
        .andExpect(status().isCreated());

    // User 8's cart is empty and independent.
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_8))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value(8))
        .andExpect(jsonPath("$.items.length()").value(0));

    // User 8 adds product 43; user 7 still sees only product 42.
    mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_8)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":43,\"quantity\":1}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_7))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].product_id").value(42));
    mockMvc
        .perform(get("/api/v1/cart").header("Authorization", USER_8))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].product_id").value(43));
  }
}
