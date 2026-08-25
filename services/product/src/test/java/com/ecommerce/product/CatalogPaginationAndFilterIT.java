package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.repository.StockReservationRepository;
import com.ecommerce.product.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pagination and filtering against a catalog large enough for the answers to differ.
 *
 * <p>The suite exists because a one-product catalog cannot discriminate: SQL {@code LIMIT} and
 * in-memory paging, a correct count and a join-inflated one, an applied {@code Sort} and a dropped
 * one all return the same single row. {@code ProductRepository.search} is a {@code @Query} +
 * {@code @EntityGraph} + {@code Page} — parsed by the HQL engine Hibernate 7 rewrote and paged by
 * the Spring Data engine 2025.1 replaced — so every one of those regressions is live.
 *
 * <p>The fixture is 5 active products across 2 categories plus 1 soft-deleted row, and the
 * soft-deleted row is created FIRST so it holds the LOWEST id: if the {@code deleted_at is null}
 * predicate were dropped, it would surface as the first element of the first page rather than
 * hiding somewhere in the tail.
 */
class CatalogPaginationAndFilterIT extends AbstractIntegrationTest {

  private static final String ALPHA = "alpha";
  private static final String BETA = "beta";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private StockReservationRepository reservationRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  /** Ids of the 5 ACTIVE products, in ascending creation order. */
  private final List<Long> activeIds = new ArrayList<>();

  private long softDeletedId;

  // The Postgres container is shared across IT classes, so the catalog is emptied on both sides:
  // before, so the totals below are exact; after, so no other suite inherits these rows.
  @BeforeEach
  void seedFixture() {
    cleanCatalog();
    transactionTemplate.executeWithoutResult(
        tx -> {
          Category alpha = categoryRepository.save(new Category("Alpha", ALPHA));
          Category beta = categoryRepository.save(new Category("Beta", BETA));

          softDeletedId = save("PAGE-DELETED", "Alpha Deleted", alpha, Instant.now()).getId();

          activeIds.add(save("PAGE-1", "Alpha One", alpha, null).getId());
          activeIds.add(save("PAGE-2", "Alpha Two zzq", alpha, null).getId());
          activeIds.add(save("PAGE-3", "Alpha Three", alpha, null).getId());
          activeIds.add(save("PAGE-4", "Beta Four zzq", beta, null).getId());
          activeIds.add(save("PAGE-5", "Beta Five", beta, null).getId());
        });
    assertThat(activeIds).isSorted();
    assertThat(softDeletedId).isLessThan(activeIds.get(0));
  }

  @AfterEach
  void clearFixture() {
    cleanCatalog();
    activeIds.clear();
  }

  @Test
  void firstPage_returnsTheTwoLowestActiveIds_andCountsAllFive() throws Exception {
    JsonNode page = getPage("/api/v1/products?page=0&size=2");

    assertThat(intAt(page, "total_elements")).isEqualTo(5);
    assertThat(intAt(page, "total_pages")).isEqualTo(3);
    assertThat(intAt(page, "page")).isZero();
    assertThat(intAt(page, "size")).isEqualTo(2);
    assertThat(idsOf(page)).containsExactly(activeIds.get(0), activeIds.get(1));
  }

  @Test
  void lastPage_returnsTheSingleHighestActiveId() throws Exception {
    JsonNode page = getPage("/api/v1/products?page=2&size=2");

    assertThat(intAt(page, "total_elements")).isEqualTo(5);
    assertThat(intAt(page, "total_pages")).isEqualTo(3);
    assertThat(idsOf(page)).containsExactly(activeIds.get(4));
  }

  // Walking all three pages is what proves the soft-deleted row is excluded from the RESULT SET and
  // not merely pushed off page 0, and that no active row is dropped or duplicated by the paging.
  @Test
  void everyPageTogether_yieldsTheFiveActiveIdsInOrder_andNeverTheSoftDeletedOne()
      throws Exception {
    List<Long> seen = new ArrayList<>();
    for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
      seen.addAll(idsOf(getPage("/api/v1/products?page=" + pageNumber + "&size=2")));
    }

    // Narrower guard first (§4h(2)): a leaked soft-deleted id also breaks exact-elements, so behind
    // it this line could never be the assertion that fails. Ahead of it, it names the leak; the
    // exact-elements pin still catches a dropped, duplicated or reordered ACTIVE id.
    assertThat(seen).doesNotContain(softDeletedId);
    assertThat(seen).containsExactlyElementsOf(activeIds);
  }

  @Test
  void categoryFilter_returnsOnlyThatCategory() throws Exception {
    JsonNode page = getPage("/api/v1/products?category=" + ALPHA);

    assertThat(intAt(page, "total_elements")).isEqualTo(3);
    for (JsonNode element : contentOf(page)) {
      assertThat(element.get("category").get("slug").textValue()).isEqualTo(ALPHA);
    }
  }

  // Upper-case query against lower-case names: the predicate lowercases both sides, and the
  // comment on ProductRepository.search records a Postgres bytea type-inference bug this shape was
  // written to avoid — Hibernate 7 rewrote exactly that inference.
  @Test
  void searchFilter_isCaseInsensitive_andMatchesTwoOfFive() throws Exception {
    JsonNode page = getPage("/api/v1/products?q=ZZQ");

    assertThat(intAt(page, "total_elements")).isEqualTo(2);
    assertThat(namesOf(page)).containsExactly("Alpha Two zzq", "Beta Four zzq");
  }

  @Test
  void categoryAndSearchCombined_intersectToOne() throws Exception {
    JsonNode page = getPage("/api/v1/products?category=" + ALPHA + "&q=ZZQ");

    assertThat(intAt(page, "total_elements")).isEqualTo(1);
    assertThat(namesOf(page)).containsExactly("Alpha Two zzq");
  }

  @Test
  void searchFilter_neverReachesTheSoftDeletedRow() throws Exception {
    JsonNode page = getPage("/api/v1/products?q=Deleted");

    assertThat(intAt(page, "total_elements")).isZero();
    assertThat(contentOf(page)).isEmpty();
  }

  // An unknown category must narrow to nothing. The failure modes worth naming are the two that a
  // broken null-guard produces: a 500, or the silently UNFILTERED list.
  @Test
  void unknownCategory_returns200WithAnEmptyPage_neverTheUnfilteredList() throws Exception {
    JsonNode page = getPage("/api/v1/products?category=nonexistent");

    assertThat(intAt(page, "total_elements")).isZero();
    assertThat(intAt(page, "total_pages")).isZero();
    assertThat(contentOf(page)).isEmpty();
  }

  @Test
  void noFilters_returnsAllFiveActive_andNotTheSoftDeletedOne() throws Exception {
    JsonNode page = getPage("/api/v1/products?page=0&size=20");

    assertThat(intAt(page, "total_elements")).isEqualTo(5);
    assertThat(idsOf(page)).containsExactlyElementsOf(activeIds);
  }

  private JsonNode getPage(String path) throws Exception {
    String body =
        mockMvc
            .perform(get(path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  /**
   * Reads a numeric envelope field, failing by NAME when it is absent.
   *
   * <p>A casing regression removes {@code total_elements} rather than changing its value, so a bare
   * {@code intAt(page, "total_elements")} dies with a NullPointerException that names neither the
   * field nor the cause. The pin has to say which key vanished.
   */
  private int intAt(JsonNode page, String field) {
    JsonNode value = page.get(field);
    assertThat(value).as("envelope key %s is absent from %s", field, page).isNotNull();
    assertThat(value.isNumber())
        .as("envelope key %s must be a number, was %s", field, value)
        .isTrue();
    return value.intValue();
  }

  private JsonNode contentOf(JsonNode page) {
    JsonNode content = page.get("content");
    assertThat(content).as("envelope key content is absent from %s", page).isNotNull();
    assertThat(content.isArray()).as("content must be an array, was %s", content).isTrue();
    return content;
  }

  private List<Long> idsOf(JsonNode page) {
    List<Long> ids = new ArrayList<>();
    contentOf(page).forEach(element -> ids.add(element.get("id").longValue()));
    return ids;
  }

  private List<String> namesOf(JsonNode page) {
    List<String> names = new ArrayList<>();
    contentOf(page).forEach(element -> names.add(element.get("name").textValue()));
    return names;
  }

  private Product save(String sku, String name, Category category, Instant deletedAt) {
    Product product = new Product();
    product.setSku(sku);
    product.setName(name);
    product.setDescription("fixture row");
    product.setPrice(new BigDecimal("10.00"));
    product.setCurrency("EUR");
    product.setCategory(category);
    product.setStockQuantity(5);
    product.setDeletedAt(deletedAt);
    return productRepository.save(product);
  }

  private void cleanCatalog() {
    transactionTemplate.executeWithoutResult(
        tx -> {
          reservationRepository.deleteAll();
          productRepository.deleteAll();
          categoryRepository.deleteAll();
        });
  }
}
