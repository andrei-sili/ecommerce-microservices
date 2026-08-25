package com.ecommerce.product;

import static com.ecommerce.product.support.JsonShape.assertIso8601Utc;
import static com.ecommerce.product.support.JsonShape.assertKeysExactly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.repository.StockReservationRepository;
import com.ecommerce.product.support.AbstractIntegrationTest;
import com.ecommerce.product.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins the EXACT key set of both error envelopes product emits: the 4-key auth/plain envelope and
 * the 5-key product-scoped one used by the reservation endpoints.
 *
 * <p>The two shapes coexist only because null fields are omitted. The regression to catch is the
 * plain envelope GAINING {@code product_id: null}, which every existing assertion (single jsonPaths
 * on {@code $.error}) passes straight through. Key sets are therefore asserted as sets, never as
 * subsets, and the {@code timestamp} format is pinned alongside them.
 */
class ErrorEnvelopePinIT extends AbstractIntegrationTest {

  private static final String CATEGORY_SLUG = "envelope-pin-cat";

  private static final String[] PLAIN_ENVELOPE_KEYS = {"error", "message", "timestamp", "path"};

  private static final String USER = TestJwt.bearer(TestJwt.token("2", List.of("USER")));

  // The 403 row authenticates with a legacy HS256 token; the yml default is RS256-only, so pin the
  // dual allowlist to keep the HS256 path reachable for this suite.
  @DynamicPropertySource
  static void dualAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private StockReservationRepository reservationRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<Long> seededProductIds = new ArrayList<>();

  @AfterEach
  void cleanUpSeededData() {
    transactionTemplate.executeWithoutResult(
        tx -> {
          productRepository.deleteAllById(seededProductIds);
          categoryRepository.findBySlug(CATEGORY_SLUG).ifPresent(categoryRepository::delete);
        });
    seededProductIds.clear();
  }

  @Test
  void noToken_onProductWrite_pins401EnvelopeToExactlyFourKeys() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/products")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validProductBody()))
            .andExpect(status().isUnauthorized())
            .andReturn();

    JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
    assertKeysExactly(envelope, PLAIN_ENVELOPE_KEYS);
    assertThat(envelope.get("error").textValue()).isEqualTo("UNAUTHORIZED");
    assertThat(envelope.get("message").textValue()).isEqualTo("Authentication required");
    assertThat(envelope.get("path").textValue()).isEqualTo("/api/v1/products");
    assertIso8601Utc(envelope, "timestamp");
    assertThat(envelope.has("product_id")).isFalse();
    assertJsonNotProblem(result);
  }

  @Test
  void nonAdminToken_onProductWrite_pins403EnvelopeToExactlyFourKeys() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/products")
                    .header("Authorization", USER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validProductBody()))
            .andExpect(status().isForbidden())
            .andReturn();

    JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
    assertKeysExactly(envelope, PLAIN_ENVELOPE_KEYS);
    assertThat(envelope.get("error").textValue()).isEqualTo("FORBIDDEN");
    assertThat(envelope.get("path").textValue()).isEqualTo("/api/v1/products");
    assertIso8601Utc(envelope, "timestamp");
    assertThat(envelope.has("product_id")).isFalse();
    assertJsonNotProblem(result);
  }

  // A domain 404 shares the record with the auth envelope: same 4 keys, and no product_id even
  // though the failure is about a product.
  @Test
  void unknownProduct_pins404EnvelopeToExactlyFourKeys() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/products/999999"))
            .andExpect(status().isNotFound())
            .andReturn();

    JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
    assertKeysExactly(envelope, PLAIN_ENVELOPE_KEYS);
    assertThat(envelope.get("error").textValue()).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(envelope.get("path").textValue()).isEqualTo("/api/v1/products/999999");
    assertIso8601Utc(envelope, "timestamp");
    assertThat(envelope.has("product_id")).isFalse();
  }

  /**
   * {@code path} is attacker-controlled: it is {@code request.getRequestURI()} echoed back into the
   * body. A percent-encoded non-ASCII segment must round-trip byte-identically — neither decoded
   * into raw UTF-8, nor double-encoded, nor mangled by a charset change on the writer path. Boot
   * 4.1 ships Tomcat 11 / Servlet 6.1, where URI decoding and response encoding both moved.
   */
  @Test
  void percentEncodedNonAsciiPath_roundTripsByteIdenticallyIntoTheEnvelope() throws Exception {
    // The request carries the raw character; the container percent-encodes it, and getRequestURI()
    // hands the ENCODED form to the envelope. Asserting the encoded spelling is what catches a
    // decode/re-encode round trip that would echo raw UTF-8 (or mojibake) back to the caller.
    String encodedPath = "/api/v1/caf%C3%A9";

    MvcResult result =
        mockMvc.perform(get("/api/v1/café")).andExpect(status().isNotFound()).andReturn();

    JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
    assertKeysExactly(envelope, PLAIN_ENVELOPE_KEYS);
    assertThat(envelope.get("error").textValue()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(envelope.get("path").textValue()).isEqualTo(encodedPath);
    assertIso8601Utc(envelope, "timestamp");
    assertJsonNotProblem(result);
  }

  @Test
  void reservationForUnknownProduct_pins422ProductScopedEnvelopeToExactlyFiveKeys()
      throws Exception {
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":999999,"quantity":1}] }
        """
            .formatted(UUID.randomUUID());

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/inventory/reservations")
                    .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();

    JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
    assertKeysExactly(envelope, "error", "message", "timestamp", "path", "product_id");
    assertThat(envelope.get("error").textValue()).isEqualTo("PRODUCT_NOT_FOUND");
    assertThat(envelope.get("path").textValue()).isEqualTo("/api/v1/inventory/reservations");
    assertThat(envelope.get("product_id").isNumber()).isTrue();
    assertThat(envelope.get("product_id").longValue()).isEqualTo(999999L);
    assertIso8601Utc(envelope, "timestamp");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("productId");
  }

  @Test
  void reservationBeyondStock_pins409ProductScopedEnvelopeToExactlyFiveKeys() throws Exception {
    long productId = seedProduct("ENVELOPE-SHORT", 1);
    UUID orderId = UUID.randomUUID();
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":%d,"quantity":5}] }
        """
            .formatted(orderId, productId);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/inventory/reservations")
                    .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isConflict())
            .andReturn();

    JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
    assertKeysExactly(envelope, "error", "message", "timestamp", "path", "product_id");
    assertThat(envelope.get("error").textValue()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(envelope.get("product_id").longValue()).isEqualTo(productId);
    assertIso8601Utc(envelope, "timestamp");
    assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
  }

  private void assertJsonNotProblem(MvcResult result) {
    String contentType = result.getResponse().getContentType();
    assertThat(contentType).isNotNull();
    assertThat(contentType).contains("application/json");
    assertThat(contentType).doesNotContain("problem");
  }

  private String validProductBody() {
    return """
        { "sku":"ENVELOPE-1","name":"Envelope","description":"desc","price":19.99,
          "currency":"EUR","category_id":1,"stock_quantity":1 }
        """;
  }

  private long seedProduct(String sku, int stock) {
    long id =
        transactionTemplate.execute(
            tx -> {
              Category category =
                  categoryRepository
                      .findBySlug(CATEGORY_SLUG)
                      .orElseGet(
                          () ->
                              categoryRepository.save(new Category("Envelope Pin", CATEGORY_SLUG)));
              Product product = new Product();
              product.setSku(sku);
              product.setName("Name " + sku);
              product.setDescription("desc");
              product.setPrice(new BigDecimal("5.00"));
              product.setCurrency("EUR");
              product.setCategory(category);
              product.setStockQuantity(stock);
              return productRepository.save(product).getId();
            });
    seededProductIds.add(id);
    return id;
  }
}
