package com.ecommerce.order.service;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartView;
import com.ecommerce.order.client.ProductReservationClient;
import com.ecommerce.order.client.ReservationRequest;
import com.ecommerce.order.client.ReservationResponse;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.dto.PageResponse;
import com.ecommerce.order.exception.EmptyCartException;
import com.ecommerce.order.exception.OrderNotCancellableException;
import com.ecommerce.order.exception.OrderNotFoundException;
import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.security.CurrentUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the order placement saga. This class is intentionally NOT {@code @Transactional}:
 * the only DB transaction is the local persist in {@link OrderPersistenceService}, so the remote
 * reservation call never runs inside an open transaction. See the saga contract in {@code
 * api_contracts.md}.
 */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  private final OrderRepository orderRepository;
  private final OrderPersistenceService persistenceService;
  private final CartClient cartClient;
  private final ProductReservationClient reservationClient;

  public OrderService(
      OrderRepository orderRepository,
      OrderPersistenceService persistenceService,
      CartClient cartClient,
      ProductReservationClient reservationClient) {
    this.orderRepository = orderRepository;
    this.persistenceService = persistenceService;
    this.cartClient = cartClient;
    this.reservationClient = reservationClient;
  }

  /**
   * Result of placement: the order plus whether it already existed (replayed idempotency key) so
   * the controller can answer 200 vs 201.
   */
  public record PlacementResult(OrderResponse order, boolean replayed) {}

  public PlacementResult placeOrder(CurrentUser user, String idempotencyKey) {
    // Fast path: a replayed Idempotency-Key returns the original order, never reserves/places
    // twice.
    Optional<OrderEntity> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return new PlacementResult(OrderResponse.from(loadWithItems(existing.get().getId())), true);
    }

    // (a) The order UUID is generated up front; it is also the reservation key.
    UUID orderId = UUID.randomUUID();

    // (b) Load the user's cart (user-scoped call, forwarding their JWT). Empty -> 422, stop.
    CartView cart = cartClient.getCart(user.bearerToken());
    if (cart == null || cart.isEmpty()) {
      throw new EmptyCartException();
    }

    // (c) Reserve + reprice in one system call. 409 -> INSUFFICIENT_STOCK, nothing persisted, stop.
    ReservationRequest request = toReservationRequest(orderId, cart);
    ReservationResponse reservation = reservationClient.reserve(orderId, request);

    // (d) One local DB transaction: order + items (authoritative reservation prices) + outbox.
    OrderEntity saved;
    try {
      saved =
          persistenceService.persistPlacedOrder(
              orderId, user.userId(), idempotencyKey, reservation);
    } catch (DataIntegrityViolationException raced) {
      // Concurrent request with the same Idempotency-Key won: release our now-orphan reservation
      // and return the order the winner created.
      compensate(orderId);
      OrderEntity winner =
          orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raced);
      return new PlacementResult(OrderResponse.from(loadWithItems(winner.getId())), true);
    } catch (RuntimeException txFailure) {
      // (e) Local tx failed: compensate by releasing the reservation, then surface the failure.
      compensate(orderId);
      throw txFailure;
    }

    // (f) Best-effort cart clear. Failure here is logged, not fatal — the idempotency key makes a
    // retry safe.
    try {
      cartClient.clearCart(user.bearerToken());
    } catch (RuntimeException ex) {
      log.warn("Failed to clear cart after placing order {}; continuing", orderId);
    }

    return new PlacementResult(OrderResponse.from(loadWithItems(saved.getId())), false);
  }

  @Transactional(readOnly = true)
  public PageResponse<OrderResponse> listOrders(CurrentUser user, int page, int size) {
    Page<OrderEntity> orders =
        orderRepository.findByUserId(
            user.userId(), PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    List<OrderResponse> content = orders.getContent().stream().map(OrderResponse::from).toList();
    return PageResponse.of(orders, content);
  }

  @Transactional(readOnly = true)
  public OrderResponse getOrder(CurrentUser user, UUID orderId) {
    return OrderResponse.from(loadOwned(user, orderId));
  }

  /** Cancel: PENDING -> CANCELLED only; releases the reservation. Any other state -> 409. */
  public OrderResponse cancelOrder(CurrentUser user, UUID orderId) {
    OrderEntity order = loadOwned(user, orderId);
    if (order.getStatus() != OrderStatus.PENDING) {
      throw new OrderNotCancellableException();
    }
    persistenceService.markCancelled(order);
    // Release the held stock. Idempotent on the order id; a second cancel is a no-op upstream.
    reservationClient.release(orderId);
    return OrderResponse.from(loadWithItems(orderId));
  }

  private OrderEntity loadOwned(CurrentUser user, UUID orderId) {
    OrderEntity order = loadWithItems(orderId);
    // Do not leak existence of other users' orders: a non-owner (non-admin) sees 404, not 403.
    if (!user.isAdmin() && !order.getUserId().equals(user.userId())) {
      throw new OrderNotFoundException();
    }
    return order;
  }

  private OrderEntity loadWithItems(UUID orderId) {
    return orderRepository.findWithItemsById(orderId).orElseThrow(OrderNotFoundException::new);
  }

  private void compensate(UUID orderId) {
    try {
      reservationClient.release(orderId);
    } catch (RuntimeException ex) {
      // Best-effort compensation: the reservation release is idempotent and can be retried later.
      log.error(
          "Compensation release failed for order {}; reservation may need manual release", orderId);
    }
  }

  private ReservationRequest toReservationRequest(UUID orderId, CartView cart) {
    List<ReservationRequest.Line> lines =
        cart.items().stream()
            .map(item -> new ReservationRequest.Line(item.productId(), item.quantity()))
            .toList();
    return new ReservationRequest(orderId, lines);
  }
}
