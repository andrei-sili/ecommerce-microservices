package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.dto.ReservationRequest;
import com.ecommerce.product.exception.ProductScopedException;
import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.model.ReservationStatus;
import com.ecommerce.product.model.StockReservation;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.repository.StockReservationRepository;
import com.ecommerce.product.service.ReservationService;
import com.ecommerce.product.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reservation behavior against a real Postgres: oversell prevention under real concurrency,
 * all-or-nothing rollback, idempotent replay/release, internal-key auth, and authoritative pricing.
 */
class ReservationIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private StockReservationRepository reservationRepository;
  @Autowired private ReservationService reservationService;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<Long> seededProductIds = new java.util.ArrayList<>();

  // The Postgres container is shared across IT classes in the JVM, so this test must leave the
  // catalog as it found it — otherwise its seeded products would inflate ProductCatalogIT's counts.
  @org.junit.jupiter.api.AfterEach
  void cleanUpSeededData() {
    transactionTemplate.executeWithoutResult(
        tx -> {
          reservationRepository.deleteAllInBatch();
          productRepository.deleteAllById(seededProductIds);
          categoryRepository.findBySlug("res-cat").ifPresent(categoryRepository::delete);
        });
    seededProductIds.clear();
  }

  private long seedProduct(String sku, int stock, BigDecimal price, String currency) {
    long id =
        transactionTemplate.execute(
            tx -> {
              Category category =
                  categoryRepository
                      .findBySlug("res-cat")
                      .orElseGet(
                          () -> categoryRepository.save(new Category("Reservations", "res-cat")));
              Product product = new Product();
              product.setSku(sku);
              product.setName("Name " + sku);
              product.setDescription("desc");
              product.setPrice(price);
              product.setCurrency(currency);
              product.setCategory(category);
              product.setStockQuantity(stock);
              return productRepository.save(product).getId();
            });
    seededProductIds.add(id);
    return id;
  }

  private int reservedQuantity(long productId) {
    return transactionTemplate.execute(
        tx -> productRepository.findById(productId).orElseThrow().getReservedQuantity());
  }

  // (a) Concurrent reservations on the same product must never oversell: with stock S and N
  // single-unit reservers, exactly S succeed and reserved_quantity ends at S — the FOR UPDATE row
  // lock + all-or-nothing must hold under real contention, not just the happy path.
  @Test
  void concurrentReservationsNeverOversell() throws Exception {
    int stock = 20;
    int threads = 60;
    long productId = seedProduct("CONC-1", stock, new BigDecimal("9.99"), "EUR");

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger insufficient = new AtomicInteger();

    List<Future<?>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                ReservationRequest request =
                    new ReservationRequest(
                        UUID.randomUUID(), List.of(new ReservationRequest.Item(productId, 1)));
                try {
                  reservationService.reserve(request);
                  succeeded.incrementAndGet();
                } catch (ProductScopedException expected) {
                  insufficient.incrementAndGet();
                }
              }));
    }
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    assertThat(succeeded.get()).isEqualTo(stock);
    assertThat(insufficient.get()).isEqualTo(threads - stock);
    assertThat(reservedQuantity(productId)).isEqualTo(stock);
  }

  // (b) All-or-nothing: if one line is short, the whole reservation aborts and nothing is reserved.
  @Test
  void allOrNothingRollbackWhenOneLineShort() throws Exception {
    long ok = seedProduct("AON-OK", 100, new BigDecimal("5.00"), "EUR");
    long short_ = seedProduct("AON-SHORT", 1, new BigDecimal("5.00"), "EUR");
    UUID orderId = UUID.randomUUID();

    String body =
        """
        { "order_id":"%s", "items":[
          {"product_id":%d,"quantity":3},
          {"product_id":%d,"quantity":3}] }
        """
            .formatted(orderId, ok, short_);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
        .andExpect(jsonPath("$.product_id").value(short_));

    // Neither product was touched, and no reservation rows persisted.
    assertThat(reservedQuantity(ok)).isZero();
    assertThat(reservedQuantity(short_)).isZero();
    assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
  }

  // (c) Idempotent replay of the same order_id adds no extra reserved_quantity.
  @Test
  void idempotentReplayDoesNotDoubleReserve() throws Exception {
    long productId = seedProduct("IDEM-1", 10, new BigDecimal("4.50"), "EUR");
    UUID orderId = UUID.randomUUID();
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":%d,"quantity":4}] }
        """
            .formatted(orderId, productId);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("RESERVED"));
    assertThat(reservedQuantity(productId)).isEqualTo(4);

    // Replay: same key, still RESERVED, reserved_quantity unchanged, still one row.
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.items[0].quantity").value(4));
    assertThat(reservedQuantity(productId)).isEqualTo(4);
    assertThat(reservationRepository.findByOrderId(orderId)).hasSize(1);
  }

  // (d) Release decrements reserved_quantity and is idempotent (releasing twice is a no-op).
  @Test
  void releaseDecrementsAndIsIdempotent() throws Exception {
    long productId = seedProduct("REL-1", 10, new BigDecimal("4.50"), "EUR");
    UUID orderId = UUID.randomUUID();
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":%d,"quantity":6}] }
        """
            .formatted(orderId, productId);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    assertThat(reservedQuantity(productId)).isEqualTo(6);

    mockMvc
        .perform(
            delete("/api/v1/inventory/reservations/" + orderId)
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isNoContent());
    assertThat(reservedQuantity(productId)).isZero();
    List<StockReservation> rows = reservationRepository.findByOrderId(orderId);
    assertThat(rows).allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);

    // Second release: idempotent no-op, still 204, reserved stays at 0.
    mockMvc
        .perform(
            delete("/api/v1/inventory/reservations/" + orderId)
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isNoContent());
    assertThat(reservedQuantity(productId)).isZero();

    // Releasing a never-seen order is also a 204 no-op.
    mockMvc
        .perform(
            delete("/api/v1/inventory/reservations/" + UUID.randomUUID())
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isNoContent());
  }

  // (e) Missing / wrong X-Internal-Api-Key is rejected with 401 — not the user JWT, not ADMIN.
  @Test
  void missingOrWrongInternalKeyIsUnauthorized() throws Exception {
    long productId = seedProduct("AUTH-1", 5, new BigDecimal("1.00"), "EUR");
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":%d,"quantity":1}] }
        """
            .formatted(UUID.randomUUID(), productId);

    // No key.
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

    // Wrong key.
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

    // DELETE is gated too.
    mockMvc
        .perform(delete("/api/v1/inventory/reservations/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());

    assertThat(reservedQuantity(productId)).isZero();
  }

  // (f) The reserve response carries authoritative current prices/currency/name + computed totals.
  @Test
  void reserveResponseCarriesAuthoritativePrices() throws Exception {
    long productId = seedProduct("PRICE-1", 50, new BigDecimal("12.50"), "EUR");
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":%d,"quantity":3}] }
        """
            .formatted(UUID.randomUUID(), productId);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("RESERVED"))
        .andExpect(jsonPath("$.currency").value("EUR"))
        .andExpect(jsonPath("$.subtotal").value(37.50))
        .andExpect(jsonPath("$.items[0].product_id").value(productId))
        .andExpect(jsonPath("$.items[0].name").value("Name PRICE-1"))
        .andExpect(jsonPath("$.items[0].unit_price").value(12.50))
        .andExpect(jsonPath("$.items[0].currency").value("EUR"))
        .andExpect(jsonPath("$.items[0].quantity").value(3))
        .andExpect(jsonPath("$.items[0].line_total").value(37.50));
  }

  // Mixed currencies across lines are rejected 422 (the order pipeline requires one currency).
  @Test
  void mixedCurrencyIsRejected() throws Exception {
    long eur = seedProduct("MIX-EUR", 10, new BigDecimal("1.00"), "EUR");
    long usd = seedProduct("MIX-USD", 10, new BigDecimal("1.00"), "USD");
    UUID orderId = UUID.randomUUID();
    String body =
        """
        { "order_id":"%s", "items":[
          {"product_id":%d,"quantity":1},
          {"product_id":%d,"quantity":1}] }
        """
            .formatted(orderId, eur, usd);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("MIXED_CURRENCY_CART"));

    assertThat(reservedQuantity(eur)).isZero();
    assertThat(reservedQuantity(usd)).isZero();
    assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
  }

  // A missing / soft-deleted product fails the whole reservation with 422 PRODUCT_NOT_FOUND.
  @Test
  void missingProductIsUnprocessable() throws Exception {
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":%d,"quantity":1}] }
        """
            .formatted(UUID.randomUUID(), 999_999L);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
        .andExpect(jsonPath("$.product_id").value(999_999L));
  }
}
