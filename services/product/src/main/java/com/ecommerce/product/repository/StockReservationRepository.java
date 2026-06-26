package com.ecommerce.product.repository;

import com.ecommerce.product.model.StockReservation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

  /** All reservation lines for an order (any status) — used for idempotency and release. */
  List<StockReservation> findByOrderId(UUID orderId);

  /**
   * Loads and locks all reservation rows for an order ({@code SELECT … FOR UPDATE}). Used by
   * commit to prevent concurrent commits from double-decrementing stock.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from StockReservation r where r.orderId = :orderId")
  List<StockReservation> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

  /**
   * Finds a batch of expired RESERVED rows for the sweeper using {@code FOR UPDATE SKIP LOCKED} so
   * concurrent sweeper instances (or a concurrent commit) never block each other.
   */
  @Query(
      value =
          "SELECT * FROM stock_reservations"
              + " WHERE status = 'RESERVED' AND expires_at < :now"
              + " ORDER BY id LIMIT :batchSize FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<StockReservation> findExpiredBatchSkipLocked(
      @Param("now") Instant now, @Param("batchSize") int batchSize);
}
