package com.ecommerce.product.service;

import com.ecommerce.product.dto.ReservationRequest;
import com.ecommerce.product.dto.ReservationResponse;
import com.ecommerce.product.exception.ProductScopedException;
import com.ecommerce.product.exception.UnprocessableEntityException;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.model.ReservationStatus;
import com.ecommerce.product.model.StockReservation;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.repository.StockReservationRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserve and release stock for orders (internal, system-to-system). Reserve is atomic and
 * all-or-nothing: every product row is locked {@code FOR UPDATE} before its availability is checked,
 * so concurrent reservations on the same product cannot oversell. Both operations are idempotent on
 * {@code order_id}.
 */
@Service
public class ReservationService {

  private final ProductRepository productRepository;
  private final StockReservationRepository reservationRepository;

  public ReservationService(
      ProductRepository productRepository, StockReservationRepository reservationRepository) {
    this.productRepository = productRepository;
    this.reservationRepository = reservationRepository;
  }

  @Transactional
  public ReservationResponse reserve(ReservationRequest request) {
    UUID orderId = request.orderId();

    List<StockReservation> existing = reservationRepository.findByOrderId(orderId);
    if (!existing.isEmpty()) {
      // Idempotent replay: return the already-reserved lines, repriced from current product data.
      // We never re-increment reserved_quantity for a known order.
      return buildResponse(orderId, activeLines(existing));
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
          new StockReservation(orderId, productId, quantity, ReservationStatus.RESERVED));
      reservedLines.add(new ReservedLine(product, quantity));
    }

    return buildResponse(orderId, reservedLines);
  }

  @Transactional
  public void release(UUID orderId) {
    List<StockReservation> reservations = reservationRepository.findByOrderId(orderId);
    for (StockReservation reservation : reservations) {
      if (reservation.getStatus() != ReservationStatus.RESERVED) {
        continue; // already released — idempotent no-op
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

  /** Reprice active reservation lines from current product rows (used by the idempotent replay). */
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

  private ReservationResponse buildResponse(UUID orderId, List<ReservedLine> lines) {
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
    return new ReservationResponse(
        orderId, ReservationStatus.RESERVED.name(), responseLines, currency, subtotal);
  }

  private record ReservedLine(Product product, int quantity) {}
}
