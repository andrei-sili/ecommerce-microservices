package com.ecommerce.product.service;

import com.ecommerce.product.dto.ReservationRequest;
import com.ecommerce.product.dto.ReservationResponse;
import com.ecommerce.product.exception.ConflictException;
import com.ecommerce.product.exception.NotFoundException;
import com.ecommerce.product.exception.ProductScopedException;
import com.ecommerce.product.exception.UnprocessableEntityException;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.model.ReservationStatus;
import com.ecommerce.product.model.StockReservation;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.repository.StockReservationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserve, commit, and release stock for orders (internal, system-to-system).
 *
 * <p>Reserve is atomic all-or-nothing: every product row is locked {@code FOR UPDATE} before its
 * availability is checked, preventing oversell under concurrency. All operations are idempotent on
 * {@code order_id}.
 *
 * <p>Wave 3 adds TTL ({@code expires_at}) to RESERVED rows, a commit path (RESERVED → COMMITTED,
 * stock permanently decremented), and a sweep helper for the scheduled sweeper.
 */
@Service
public class ReservationService {

  private final ProductRepository productRepository;
  private final StockReservationRepository reservationRepository;
  private final int reservationTtlMinutes;

  public ReservationService(
      ProductRepository productRepository,
      StockReservationRepository reservationRepository,
      @Value("${reservation.ttl-minutes:30}") int reservationTtlMinutes) {
    this.productRepository = productRepository;
    this.reservationRepository = reservationRepository;
    this.reservationTtlMinutes = reservationTtlMinutes;
  }

  @Transactional
  public ReservationResponse reserve(ReservationRequest request) {
    UUID orderId = request.orderId();

    List<StockReservation> existing = reservationRepository.findByOrderId(orderId);
    if (!existing.isEmpty()) {
      // Idempotent replay: return the already-reserved lines, repriced from current product data.
      return buildResponse(orderId, ReservationStatus.RESERVED.name(), activeLines(existing));
    }

    // Aggregate duplicate product lines (UNIQUE(order_id, product_id) means one row per product).
    Map<Long, Integer> requested = new LinkedHashMap<>();
    for (ReservationRequest.Item item : request.items()) {
      requested.merge(item.productId(), item.quantity(), Integer::sum);
    }

    // Lock product rows in ascending id order — a stable order avoids deadlocks when two orders
    // reserve overlapping products concurrently.
    List<Long> productIds = new ArrayList<>(requested.keySet());
    productIds.sort(Comparator.naturalOrder());

    List<ReservedLine> reservedLines = new ArrayList<>();
    String currency = null;
    Instant expiresAt = Instant.now().plus(reservationTtlMinutes, ChronoUnit.MINUTES);

    for (Long productId : productIds) {
      Product product =
          productRepository
              .findByIdForUpdate(productId)
              .orElseThrow(
                  () ->
                      new ProductScopedException(
                          HttpStatus.UNPROCESSABLE_ENTITY,
                          "PRODUCT_NOT_FOUND",
                          "Product " + productId + " does not exist",
                          productId));

      if (currency == null) {
        currency = product.getCurrency();
      } else if (!currency.equals(product.getCurrency())) {
        throw new UnprocessableEntityException(
            "MIXED_CURRENCY_CART", "All reserved items must share a single currency");
      }

      int quantity = requested.get(productId);
      int available = product.getStockQuantity() - product.getReservedQuantity();
      if (available < quantity) {
        throw new ProductScopedException(
            HttpStatus.CONFLICT,
            "INSUFFICIENT_STOCK",
            "Insufficient stock for product " + productId,
            productId);
      }

      product.setReservedQuantity(product.getReservedQuantity() + quantity);
      reservationRepository.save(
          new StockReservation(orderId, productId, quantity, ReservationStatus.RESERVED, expiresAt));
      reservedLines.add(new ReservedLine(product, quantity));
    }

    return buildResponse(orderId, ReservationStatus.RESERVED.name(), reservedLines);
  }

  /**
   * Commit a reservation: for each RESERVED line, set COMMITTED and permanently decrement
   * stock_quantity and reserved_quantity. Idempotent: all-COMMITTED → 200 no-op. RELEASED/absent
   * line → 409 (hold expired, Order handles reconciliation). No rows → 404.
   */
  @Transactional
  public ReservationResponse commit(UUID orderId) {
    List<StockReservation> lines = reservationRepository.findByOrderIdForUpdate(orderId);

    if (lines.isEmpty()) {
      throw new NotFoundException(
          "RESERVATION_NOT_FOUND", "No reservation found for order " + orderId);
    }

    // First pass: classify — any RELEASED line aborts the commit immediately.
    boolean allCommitted = true;
    for (StockReservation line : lines) {
      switch (line.getStatus()) {
        case RELEASED ->
            throw new ConflictException(
                "RESERVATION_NOT_ACTIVE",
                "Reservation for order " + orderId + " is no longer active");
        case RESERVED -> allCommitted = false;
        case COMMITTED -> {} // will be a no-op
      }
    }

    // Idempotent: all lines already committed — return summary without touching stock.
    if (allCommitted) {
      return buildCommitSummary(orderId, lines);
    }

    // Second pass: commit RESERVED lines in product-id ascending order to prevent deadlocks.
    List<StockReservation> toCommit =
        lines.stream()
            .filter(r -> r.getStatus() == ReservationStatus.RESERVED)
            .sorted(Comparator.comparing(StockReservation::getProductId))
            .toList();

    List<ReservedLine> responseLines = new ArrayList<>();
    for (StockReservation line : toCommit) {
      Product product =
          productRepository
              .findByIdForUpdate(line.getProductId())
              .orElseThrow(
                  () ->
                      new UnprocessableEntityException(
                          "PRODUCT_NOT_FOUND",
                          "Product " + line.getProductId() + " does not exist"));
      product.setStockQuantity(Math.max(0, product.getStockQuantity() - line.getQuantity()));
      product.setReservedQuantity(Math.max(0, product.getReservedQuantity() - line.getQuantity()));
      line.setStatus(ReservationStatus.COMMITTED);
      responseLines.add(new ReservedLine(product, line.getQuantity()));
    }

    responseLines.sort(Comparator.comparing(rl -> rl.product().getId()));
    return buildResponse(orderId, ReservationStatus.COMMITTED.name(), responseLines);
  }

  /**
   * Release only RESERVED lines (→ RELEASED, reserved_quantity decremented). COMMITTED lines are
   * never released — a committed sale must not silently restock. Already RELEASED/absent → 204
   * idempotent no-op.
   */
  @Transactional
  public void release(UUID orderId) {
    List<StockReservation> reservations = reservationRepository.findByOrderId(orderId);
    for (StockReservation reservation : reservations) {
      if (reservation.getStatus() != ReservationStatus.RESERVED) {
        // COMMITTED → never released; RELEASED → already done. Both are idempotent no-ops.
        continue;
      }
      productRepository
          .findByIdForUpdate(reservation.getProductId())
          .ifPresent(
              product ->
                  product.setReservedQuantity(
                      Math.max(0, product.getReservedQuantity() - reservation.getQuantity())));
      reservation.setStatus(ReservationStatus.RELEASED);
    }
  }

  /**
   * Releases one batch of expired RESERVED rows, returning the count. Called in a loop by {@link
   * com.ecommerce.product.service.ReservationSweeper}; each call runs in its own transaction.
   */
  @Transactional
  public int sweepExpired(int batchSize) {
    Instant now = Instant.now();
    List<StockReservation> expired =
        reservationRepository.findExpiredBatchSkipLocked(now, batchSize);
    for (StockReservation reservation : expired) {
      productRepository
          .findByIdForUpdate(reservation.getProductId())
          .ifPresent(
              product ->
                  product.setReservedQuantity(
                      Math.max(0, product.getReservedQuantity() - reservation.getQuantity())));
      reservation.setStatus(ReservationStatus.RELEASED);
    }
    return expired.size();
  }

  // --- private helpers ---

  /** Reprice active RESERVED lines from current product rows (used by the idempotent replay). */
  private List<ReservedLine> activeLines(List<StockReservation> reservations) {
    List<ReservedLine> lines = new ArrayList<>();
    for (StockReservation reservation : reservations) {
      if (reservation.getStatus() != ReservationStatus.RESERVED) {
        continue;
      }
      Product product =
          productRepository
              .findByIdAndDeletedAtIsNull(reservation.getProductId())
              .orElseThrow(
                  () ->
                      new ProductScopedException(
                          HttpStatus.UNPROCESSABLE_ENTITY,
                          "PRODUCT_NOT_FOUND",
                          "Product " + reservation.getProductId() + " does not exist",
                          reservation.getProductId()));
      lines.add(new ReservedLine(product, reservation.getQuantity()));
    }
    lines.sort(Comparator.comparing(line -> line.product().getId()));
    return lines;
  }

  /** Builds a commit summary for the idempotent case (all lines already COMMITTED). */
  private ReservationResponse buildCommitSummary(UUID orderId, List<StockReservation> lines) {
    List<ReservedLine> responseLines = new ArrayList<>();
    List<StockReservation> sorted =
        lines.stream().sorted(Comparator.comparing(StockReservation::getProductId)).toList();
    for (StockReservation line : sorted) {
      Product product =
          productRepository
              .findByIdAndDeletedAtIsNull(line.getProductId())
              .orElseThrow(
                  () ->
                      new UnprocessableEntityException(
                          "PRODUCT_NOT_FOUND",
                          "Product " + line.getProductId() + " does not exist"));
      responseLines.add(new ReservedLine(product, line.getQuantity()));
    }
    return buildResponse(orderId, ReservationStatus.COMMITTED.name(), responseLines);
  }

  private ReservationResponse buildResponse(
      UUID orderId, String status, List<ReservedLine> lines) {
    String currency = lines.isEmpty() ? null : lines.get(0).product().getCurrency();
    BigDecimal subtotal = BigDecimal.ZERO;
    List<ReservationResponse.Line> responseLines = new ArrayList<>();
    for (ReservedLine line : lines) {
      Product product = line.product();
      BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(line.quantity()));
      subtotal = subtotal.add(lineTotal);
      responseLines.add(
          new ReservationResponse.Line(
              product.getId(),
              product.getName(),
              product.getPrice(),
              product.getCurrency(),
              line.quantity(),
              lineTotal));
    }
    return new ReservationResponse(orderId, status, responseLines, currency, subtotal);
  }

  private record ReservedLine(Product product, int quantity) {}
}
