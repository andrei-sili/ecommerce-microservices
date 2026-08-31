package com.ecommerce.product;

import static com.ecommerce.product.support.JsonShape.assertIso8601Utc;
import static com.ecommerce.product.support.JsonShape.assertKeysExactly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the serialized shape of the catalog reads against a real Postgres: the exact key set of a
 * product, of a list element, of the pagination envelope and of a category, plus the ISO-8601
 * rendering of every date field.
 *
 * <p>Existing suites assert individual jsonPaths, which cannot see an ADDED key and never look at
 * the date format. Both are the shapes a Jackson naming/inclusion/date default moves silently.
 */
class ResponseShapePinIT extends AbstractIntegrationTest {

  /** Substring the catalog filter isolates this suite's rows by, so counts stay deterministic. */
  private static final String SEARCH_TOKEN = "shapepin";

  private static final String CATEGORY_SLUG = "shapepin-cat";

  /** Every camelCase spelling that must never appear on a REST body (contract: snake_case). */
  private static final String[] CAMEL_CASE_LEAKS = {
    "stockQuantity", "createdAt", "updatedAt", "totalElements", "totalPages", "categoryId"
  };

  private static final String[] PRODUCT_KEYS = {
    "id",
    "sku",
    "name",
    "description",
    "price",
    "currency",
    "category",
    "stock_quantity",
    "available",
    "created_at",
    "updated_at"
  };

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<Long> seededProductIds = new ArrayList<>();

  // The Postgres container is shared across IT classes in the JVM, so this suite must leave the
  // catalog as it found it — otherwise its rows would inflate ProductCatalogIT's counts.
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
  void productDetail_pinsExactSnakeCaseKeySetAndIso8601Dates() throws Exception {
    long id = seedProduct("SHAPE-DETAIL", SEARCH_TOKEN + " detail tee", "Soft cotton tee");

    String body = getBody("/api/v1/products/" + id);
    JsonNode product = objectMapper.readTree(body);

    // The whole-body camelCase scan runs BEFORE the key-set pins on purpose. A casing drift also
    // breaks key-set equality, so behind them this scan could never be the assertion that fails —
    // present as coverage, dead as a guard. First, it names the offending spelling; the key sets
    // then still catch an ADDED or REMOVED key, which the scan cannot see.
    assertThat(body).doesNotContain(CAMEL_CASE_LEAKS);
    assertKeysExactly(product, PRODUCT_KEYS);
    assertKeysExactly(product.get("category"), "id", "name", "slug");
    assertIso8601Utc(product, "created_at");
    assertIso8601Utc(product, "updated_at");
  }

  // The wider envelopes in this fleet only stay wider because null-valued fields are omitted. A
  // product without a description is the local witness for that inclusion rule: if it stops
  // omitting, the key reappears as null here first.
  @Test
  void productDetail_withoutDescription_omitsTheNullKeyEntirely() throws Exception {
    long id = seedProduct("SHAPE-NODESC", SEARCH_TOKEN + " bare item", null);

    String body = getBody("/api/v1/products/" + id);
    JsonNode product = objectMapper.readTree(body);

    assertThat(body).doesNotContain("\"description\"");
    assertKeysExactly(
        product,
        "id",
        "sku",
        "name",
        "price",
        "currency",
        "category",
        "stock_quantity",
        "available",
        "created_at",
        "updated_at");
  }

  @Test
  void productsList_pinsPaginationEnvelopeAndElementShape() throws Exception {
    seedProduct("SHAPE-LIST-1", SEARCH_TOKEN + " list one", "First listed");
    seedProduct("SHAPE-LIST-2", SEARCH_TOKEN + " list two", "Second listed");

    String body = getBody("/api/v1/products?q=" + SEARCH_TOKEN + "&page=0&size=20");
    JsonNode page = objectMapper.readTree(body);

    assertThat(body).doesNotContain(CAMEL_CASE_LEAKS);
    assertKeysExactly(page, "content", "page", "size", "total_elements", "total_pages");
    assertThat(page.get("total_elements").intValue()).isEqualTo(2);
    assertThat(page.get("total_pages").intValue()).isEqualTo(1);

    JsonNode content = page.get("content");
    assertThat(content.isArray()).isTrue();
    assertThat(content.size()).isEqualTo(2);
    for (JsonNode element : content) {
      assertKeysExactly(element, PRODUCT_KEYS);
      assertKeysExactly(element.get("category"), "id", "name", "slug");
      assertIso8601Utc(element, "created_at");
      assertIso8601Utc(element, "updated_at");
    }
  }

  @Test
  void categoriesList_pinsExactElementKeySet() throws Exception {
    seedProduct("SHAPE-CAT", SEARCH_TOKEN + " category owner", "Owns the category");

    String body = getBody("/api/v1/categories");
    JsonNode categories = objectMapper.readTree(body);

    assertThat(categories.isArray()).isTrue();
    assertThat(categories.size()).isPositive();
    // The entity carries created_at/updated_at; the response deliberately does not expose them.
    // Both scans precede the key-set pin so each can be the assertion that fails.
    assertThat(body).doesNotContain("created_at", "updated_at");
    assertThat(body).doesNotContain(CAMEL_CASE_LEAKS);
    List<String> slugs = new ArrayList<>();
    for (JsonNode category : categories) {
      assertKeysExactly(category, "id", "name", "slug");
      slugs.add(category.get("slug").textValue());
    }
    assertThat(slugs).contains(CATEGORY_SLUG);
  }

  private String getBody(String path) throws Exception {
    return mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private long seedProduct(String sku, String name, String description) {
    long id =
        transactionTemplate.execute(
            tx -> {
              Category category =
                  categoryRepository
                      .findBySlug(CATEGORY_SLUG)
                      .orElseGet(
                          () -> categoryRepository.save(new Category("Shape Pin", CATEGORY_SLUG)));
              Product product = new Product();
              product.setSku(sku);
              product.setName(name);
              product.setDescription(description);
              product.setPrice(new BigDecimal("19.99"));
              product.setCurrency("EUR");
              product.setCategory(category);
              product.setStockQuantity(120);
              return productRepository.save(product).getId();
            });
    seededProductIds.add(id);
    return id;
  }
}
