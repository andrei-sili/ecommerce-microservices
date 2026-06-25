package com.ecommerce.product.repository;

import com.ecommerce.product.model.StockReservation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

  /** All reservation lines for an order (any status) — used for idempotency and release. */
  List<StockReservation> findByOrderId(UUID orderId);
}
