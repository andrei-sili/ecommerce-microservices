package com.ecommerce.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.dto.CategoryResponse;
import com.ecommerce.product.dto.InventoryResponse;
import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.exception.ConflictException;
import com.ecommerce.product.exception.NotFoundException;
import com.ecommerce.product.exception.UnprocessableEntityException;
import com.ecommerce.product.security.JwtService;
import com.ecommerce.product.security.RestAccessDeniedHandler;
import com.ecommerce.product.security.RestAuthenticationEntryPoint;
import com.ecommerce.product.service.ProductService;
import com.ecommerce.product.support.JwtTestKeys;
import com.ecommerce.product.support.TestJwt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import({
  com.ecommerce.product.config.SecurityConfig.class,
  JwtService.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class,
  com.ecommerce.product.exception.GlobalExceptionHandler.class,
  com.ecommerce.product.support.JwtSliceTestConfig.class
})
@TestPropertySource(
    properties = {
      "security.jwt.secret=" + TestJwt.SECRET,
      // HS256-only in this slice: these controller tests mint HS256 tokens, so no RSA public key is
      // needed. The RS256 dual-accept matrix is covered by the full-context suites.
      "security.jwt.accepted-algs=HS256",
      "security.internal-api-key=test-internal-api-key"
    })
class ProductControllerWebTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private ProductService productService;

  @DynamicPropertySource
  static void jwtPublicKey(DynamicPropertyRegistry registry) {
    // Override the yml public-key placeholder with a runtime-generated key so the dual-accept
    // validator loads valid material; these controller tests exercise HS256 tokens.
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
  }

  private static final String ADMIN = TestJwt.bearer(TestJwt.token("1", List.of("ADMIN")));
  private static final String USER = TestJwt.bearer(TestJwt.token("2", List.of("USER")));

  private ProductResponse sampleProduct() {
    return new ProductResponse(
        42L,
        "SKU-1",
        "Black T-Shirt",
        "desc",
        new BigDecimal("19.99"),
        "EUR",
        new CategoryResponse(3L, "Apparel", "apparel"),
        120,
        true,
        Instant.parse("2026-06-25T10:00:00Z"),
        Instant.parse("2026-06-25T10:00:00Z"));
  }

  private String validBody() {
    return """
        { "sku":"SKU-1","name":"Black T-Shirt","description":"desc","price":19.99,
          "currency":"EUR","category_id":3,"stock_quantity":120 }
        """;
  }

  // ---- Public reads ----

  @Test
  void getProduct_isPublic_returnsProductWithNestedCategory() throws Exception {
    when(productService.get(42L)).thenReturn(sampleProduct());
    mockMvc
        .perform(get("/api/v1/products/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(42))
        .andExpect(jsonPath("$.price").value(19.99))
        .andExpect(jsonPath("$.stock_quantity").value(120))
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.category.slug").value("apparel"));
  }

  @Test
  void getProduct_missing_returns404WithStandardBody() throws Exception {
    when(productService.get(99L))
        .thenThrow(new NotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
    mockMvc
        .perform(get("/api/v1/products/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
        .andExpect(jsonPath("$.path").value("/api/v1/products/99"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getInventory_isPublic() throws Exception {
    when(productService.getInventory(42L)).thenReturn(new InventoryResponse(42L, 120, 0, true));
    mockMvc
        .perform(get("/api/v1/products/42/inventory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product_id").value(42))
        .andExpect(jsonPath("$.reserved_quantity").value(0))
        .andExpect(jsonPath("$.available").value(true));
  }

  // ---- Write authorization ----

  @Test
  void createProduct_noToken_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    verifyNoInteractions(productService);
  }

  @Test
  void createProduct_nonAdmin_returns403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    verifyNoInteractions(productService);
  }

  @Test
  void createProduct_expiredToken_returns401() throws Exception {
    String expired = TestJwt.bearer(TestJwt.expiredToken("1", List.of("ADMIN")));
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(productService);
  }

  @Test
  void createProduct_admin_returns201WithLocation() throws Exception {
    when(productService.create(any())).thenReturn(sampleProduct());
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/products/42"))
        .andExpect(jsonPath("$.sku").value("SKU-1"));
  }

  @Test
  void createProduct_duplicateSku_returns409() throws Exception {
    when(productService.create(any()))
        .thenThrow(new ConflictException("SKU_ALREADY_EXISTS", "exists"));
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("SKU_ALREADY_EXISTS"));
  }

  @Test
  void createProduct_missingCategory_returns422() throws Exception {
    when(productService.create(any()))
        .thenThrow(new UnprocessableEntityException("CATEGORY_NOT_FOUND", "missing"));
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("CATEGORY_NOT_FOUND"));
  }

  @Test
  void createProduct_invalidBody_returns400() throws Exception {
    String invalid =
        "{ \"sku\":\"\",\"name\":\"\",\"price\":-1,\"category_id\":3,\"stock_quantity\":-5 }";
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    verifyNoInteractions(productService);
  }

  @Test
  void deleteProduct_admin_returns204AndIsIdempotent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/products/42").header("Authorization", ADMIN))
        .andExpect(status().isNoContent());
    // Deleting again -> still 204 (service is idempotent, controller maps the same).
    mockMvc
        .perform(delete("/api/v1/products/42").header("Authorization", ADMIN))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteProduct_nonAdmin_returns403() throws Exception {
    mockMvc
        .perform(delete("/api/v1/products/42").header("Authorization", USER))
        .andExpect(status().isForbidden());
    verifyNoInteractions(productService);
  }

  @Test
  void adjustInventory_insufficientStock_returns409() throws Exception {
    when(productService.adjustInventory(eq(42L), any()))
        .thenThrow(new ConflictException("INSUFFICIENT_STOCK", "negative"));
    mockMvc
        .perform(
            patch("/api/v1/products/42/inventory")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":-1000}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"));
  }

  @Test
  void adjustInventory_nonAdmin_returns403() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/products/42/inventory")
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":10}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(productService);
  }
}
