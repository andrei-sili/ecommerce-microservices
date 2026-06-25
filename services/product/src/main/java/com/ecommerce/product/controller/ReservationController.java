package com.ecommerce.product.controller;

import com.ecommerce.product.dto.ReservationRequest;
import com.ecommerce.product.dto.ReservationResponse;
import com.ecommerce.product.service.ReservationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (system-to-system) inventory reservation API. Authenticated by the {@code
 * X-Internal-Api-Key} header (see {@code InternalApiKeyFilter}), not a user JWT and not the ADMIN
 * role. Order reserves stock at placement and releases on cancellation/compensation.
 *
 * <p>Wave 3 forward-note (not implemented here): when {@code PaymentCompleted} arrives, the
 * reservation is committed via {@code POST /api/v1/inventory/reservations/{orderId}/commit}
 * (decrement both {@code stock_quantity} and {@code reserved_quantity}). Wave 2 only reserves and
 * releases.
 */
@RestController
@RequestMapping("/api/v1/inventory/reservations")
public class ReservationController {

  private final ReservationService reservationService;

  public ReservationController(ReservationService reservationService) {
    this.reservationService = reservationService;
  }

  @PostMapping
  public ResponseEntity<ReservationResponse> reserve(
      @Valid @RequestBody ReservationRequest request) {
    ReservationResponse reservation = reservationService.reserve(request);
    return ResponseEntity.created(
            URI.create("/api/v1/inventory/reservations/" + reservation.orderId()))
        .body(reservation);
  }

  @DeleteMapping("/{orderId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID orderId) {
    reservationService.release(orderId);
  }
}
