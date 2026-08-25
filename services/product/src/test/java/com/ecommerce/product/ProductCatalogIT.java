package com.ecommerce.product;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.support.AbstractIntegrationTest;
import com.ecommerce.product.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end flow against a real Postgres: create category/product, list, filter, inventory. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductCatalogIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  private static final String ADMIN = TestJwt.bearer(TestJwt.token("1", List.of("ADMIN")));

  // The catalog flow authenticates with a legacy HS256 admin token; phase-3 flipped the yml default
  // to RS256-only, so pin the dual allowlist to keep the HS256 path reachable for this suite.
  @DynamicPropertySource
  static void dualAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
  }

  @Test
  @Order(1)
  void fullCatalogAndInventoryFlow() throws Exception {
    // Create a category as ADMIN.
    String categoryJson =
        mockMvc
            .perform(
                post("/api/v1/categories")
                    .header("Authorization", ADMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Apparel\",\"slug\":\"apparel\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long categoryId = objectMapper.readTree(categoryJson).get("id").asLong();

    // Duplicate slug -> 409.
    mockMvc
        .perform(
            post("/api/v1/categories")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel 2\",\"slug\":\"apparel\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("SLUG_ALREADY_EXISTS"));

    // Create a product as ADMIN.
    String productBody =
        """
        { "sku":"TSHIRT-BLK-M","name":"Black T-Shirt","description":"Soft cotton tee",
          "price":19.99,"currency":"EUR","category_id":%d,"stock_quantity":120 }
        """
            .formatted(categoryId);
    String productJson =
        mockMvc
            .perform(
                post("/api/v1/products")
                    .header("Authorization", ADMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(productBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.category.slug").value("apparel"))
            .andExpect(jsonPath("$.price").value(19.99))
            .andExpect(jsonPath("$.available").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long productId = objectMapper.readTree(productJson).get("id").asLong();

    // Duplicate SKU -> 409.
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(productBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("SKU_ALREADY_EXISTS"));

    // Non-existent category -> 422.
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sku\":\"X-1\",\"name\":\"X\",\"price\":1.00,\"currency\":\"EUR\",\"category_id\":999999,\"stock_quantity\":1}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("CATEGORY_NOT_FOUND"));

    // Public detail read.
    mockMvc
        .perform(get("/api/v1/products/" + productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sku").value("TSHIRT-BLK-M"));

    // Public paginated list with envelope. The hasSize(5) guard pins the envelope to exactly
    // content/page/size/total_elements/total_pages — the individual jsonPaths below cannot see a
    // sixth key being added.
    mockMvc
        .perform(get("/api/v1/products?page=0&size=20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.total_elements").value(1))
        .andExpect(jsonPath("$.total_pages").value(1));

    // Filter by category slug + case-insensitive search.
    mockMvc
        .perform(get("/api/v1/products?category=apparel&q=BLACK"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_elements").value(1));

    // Filter that matches nothing.
    mockMvc
        .perform(get("/api/v1/products?q=nonexistent"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_elements").value(0));

    // Inventory view.
    mockMvc
        .perform(get("/api/v1/products/" + productId + "/inventory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product_id").value(productId))
        .andExpect(jsonPath("$.stock_quantity").value(120))
        .andExpect(jsonPath("$.reserved_quantity").value(0))
        .andExpect(jsonPath("$.available").value(true));

    // Inventory adjust (restock).
    mockMvc
        .perform(
            patch("/api/v1/products/" + productId + "/inventory")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":30}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stock_quantity").value(150));

    // Inventory adjust that would go negative -> 409.
    mockMvc
        .perform(
            patch("/api/v1/products/" + productId + "/inventory")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":-1000}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"));

    // Full replace via PUT.
    String updateBody =
        """
        { "sku":"TSHIRT-BLK-M","name":"Black T-Shirt v2","description":"Updated",
          "price":24.50,"currency":"EUR","category_id":%d,"stock_quantity":10 }
        """
            .formatted(categoryId);
    mockMvc
        .perform(
            put("/api/v1/products/" + productId)
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Black T-Shirt v2"))
        .andExpect(jsonPath("$.price").value(24.50))
        .andExpect(jsonPath("$.stock_quantity").value(10));

    // Soft delete -> 204, idempotent.
    mockMvc
        .perform(delete("/api/v1/products/" + productId).header("Authorization", ADMIN))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/v1/products/" + productId).header("Authorization", ADMIN))
        .andExpect(status().isNoContent());

    // After soft delete the product is gone from reads.
    mockMvc
        .perform(get("/api/v1/products/" + productId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
    mockMvc
        .perform(get("/api/v1/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_elements").value(0));
  }

  @Test
  @Order(2)
  void price_isPersistedWithTwoDecimalPrecision() throws Exception {
    String listing =
        mockMvc
            .perform(get("/api/v1/categories"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode categories = objectMapper.readTree(listing);
    long categoryId = categories.get(0).get("id").asLong();

    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "sku":"PRECISION-1","name":"Precision","price":9.9,"currency":"EUR",
                      "category_id":%d,"stock_quantity":5 }
                    """
                        .formatted(categoryId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.price").value(9.90));
  }
}
