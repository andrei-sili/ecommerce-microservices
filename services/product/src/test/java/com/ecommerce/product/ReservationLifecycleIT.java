package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.model.ReservationStatus;
import com.ecommerce.product.model.StockReservation;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.repository.StockReservationRepository;
import com.ecommerce.product.service.ReservationSweeper;
import com.ecommerce.product.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wave 3 reservation lifecycle tests: commit RESERVED→COMMITTED, commit idempotency, commit-after-
 * release (409), release of COMMITTED (no restock), and sweeper expiry.
 */
class ReservationLifecycleIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private StockReservationRepository reservationRepository;
  @Autowired private ReservationSweeper sweeper;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<Long> seededProductIds = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    transactionTemplate.executeWithoutResult(
        tx -> {
          reservationRepository.deleteAllInBatch();
          productRepository.deleteAllById(seededProductIds);
          categoryRepository.findBySlug("lc-cat").ifPresent(categoryRepository::delete);
        });
    seededProductIds.clear();
  }

  private long seedProduct(String sku, int stock, BigDecimal price) {
    long id =
        transactionTemplate.execute(
            tx -> {
              Category category =
                  categoryRepository
                      .findBySlug("lc-cat")
                      .orElseGet(
                          () -> categoryRepository.save(new Category("Lifecycle", "lc-cat")));
              Product p = new Product();
              p.setSku(sku);
              p.setName("Name " + sku);
              p.setDescription("desc");
              p.setPrice(price);
              p.setCurrency("EUR");
              p.setCategory(category);
              p.setStockQuantity(stock);
              return productRepository.save(p).getId();
            });
    seededProductIds.add(id);
    return id;
  }

  private int stockQuantity(long productId) {
    return transactionTemplate.execute(
        tx -> productRepository.findById(productId).orElseThrow().getStockQuantity());
  }

  private int reservedQuantity(long productId) {
    return transactionTemplate.execute(
        tx -> productRepository.findById(productId).orElseThrow().getReservedQuantity());
  }

  // Helper: reserve via HTTP using the test internal key.
  private void httpReserve(UUID orderId, long productId, int qty) throws Exception {
    String body =
        """
        {"order_id":"%s","items":[{"product_id":%d,"quantity":%d}]}
        """
            .formatted(orderId, productId, qty);
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
  }

  // (1) Commit RESERVED → COMMITTED: stock_quantity decremented once, reserved_quantity zeroed.
  @Test
  void commitReservedDecrementStock() throws Exception {
    long productId = seedProduct("CMT-1", 10, new BigDecimal("5.00"));
    UUID orderId = UUID.randomUUID();

    httpReserve(orderId, productId, 3);
    assertThat(reservedQuantity(productId)).isEqualTo(3);
    assertThat(stockQuantity(productId)).isEqualTo(10);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations/" + orderId + "/commit")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMMITTED"))
        .andExpect(jsonPath("$.items[0].quantity").value(3));

    // stock permanently decremented; reserved freed
    assertThat(stockQuantity(productId)).isEqualTo(7);
    assertThat(reservedQuantity(productId)).isZero();

    List<StockReservation> rows = reservationRepository.findByOrderId(orderId);
    assertThat(rows).allMatch(r -> r.getStatus() == ReservationStatus.COMMITTED);
  }

  // (2) Commit is idempotent: calling it twice must decrement stock only once.
  @Test
  void commitIsIdempotentSecondCallNoExtraDecrement() throws Exception {
    long productId = seedProduct("CMT-2", 20, new BigDecimal("2.00"));
    UUID orderId = UUID.randomUUID();

    httpReserve(orderId, productId, 5);

    // First commit
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations/" + orderId + "/commit")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMMITTED"));

    assertThat(stockQuantity(productId)).isEqualTo(15);
    assertThat(reservedQuantity(productId)).isZero();

    // Second commit — must be a no-op
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations/" + orderId + "/commit")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMMITTED"));

    // Still 15, not 10
    assertThat(stockQuantity(productId)).isEqualTo(15);
    assertThat(reservedQuantity(productId)).isZero();
  }

  // (3) Commit after release → 409 RESERVATION_NOT_ACTIVE (expired / cancelled hold).
  @Test
  void commitAfterReleaseReturns409() throws Exception {
    long productId = seedProduct("CMT-3", 10, new BigDecimal("3.00"));
    UUID orderId = UUID.randomUUID();

    httpReserve(orderId, productId, 2);

    // Release (simulate order cancellation or sweeper expiry)
    mockMvc
        .perform(
            delete("/api/v1/inventory/reservations/" + orderId)
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isNoContent());

    // Now try to commit the released reservation
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations/" + orderId + "/commit")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("RESERVATION_NOT_ACTIVE"));

    // Stock must remain untouched (no oversell)
    assertThat(stockQuantity(productId)).isEqualTo(10);
    assertThat(reservedQuantity(productId)).isZero();
  }

  // (4) Release of a COMMITTED reservation is a silent no-op (204, no restock).
  @Test
  void releaseOfCommittedIsNoOp() throws Exception {
    long productId = seedProduct("CMT-4", 10, new BigDecimal("4.00"));
    UUID orderId = UUID.randomUUID();

    httpReserve(orderId, productId, 4);

    // Commit
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations/" + orderId + "/commit")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isOk());

    assertThat(stockQuantity(productId)).isEqualTo(6);
    assertThat(reservedQuantity(productId)).isZero();

    // Release after commit: must be idempotent no-op, not restock
    mockMvc
        .perform(
            delete("/api/v1/inventory/reservations/" + orderId)
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isNoContent());

    // Stock must still be 6, not restocked to 10
    assertThat(stockQuantity(productId)).isEqualTo(6);
    assertThat(reservedQuantity(productId)).isZero();

    List<StockReservation> rows = reservationRepository.findByOrderId(orderId);
    assertThat(rows).allMatch(r -> r.getStatus() == ReservationStatus.COMMITTED);
  }

  // (5) Sweeper releases expired RESERVED rows and restores reserved_quantity.
  @Test
  void sweeperReleasesExpiredReservationAndRestoresReservedQty() {
    long productId = seedProduct("SWP-1", 15, new BigDecimal("1.00"));
    UUID orderId = UUID.randomUUID();

    // Insert a reservation row directly with an already-expired expires_at.
    transactionTemplate.executeWithoutResult(
        tx -> {
          Product product = productRepository.findById(productId).orElseThrow();
          product.setReservedQuantity(7);

          Instant alreadyExpired = Instant.now().minus(1, ChronoUnit.HOURS);
          StockReservation reservation =
              new StockReservation(
                  orderId, productId, 7, ReservationStatus.RESERVED, alreadyExpired);
          reservationRepository.save(reservation);
        });

    assertThat(reservedQuantity(productId)).isEqualTo(7);

    // Run the sweeper directly (the @Scheduled trigger is disabled in tests).
    sweeper.sweep();

    // reserved_quantity restored to 0; row status → RELEASED
    assertThat(reservedQuantity(productId)).isZero();
    List<StockReservation> rows = reservationRepository.findByOrderId(orderId);
    assertThat(rows).allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
  }

  // (6) Commit of a non-existent reservation → 404 RESERVATION_NOT_FOUND.
  @Test
  void commitNonExistentReturns404() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory/reservations/" + UUID.randomUUID() + "/commit")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("RESERVATION_NOT_FOUND"));
  }
}
