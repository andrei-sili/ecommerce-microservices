package com.ecommerce.order.service;

import com.ecommerce.order.client.ReservationResponse;
import com.ecommerce.order.event.OutboxService;
import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single local DB transaction of the placement saga (step 4): insert the order, its line
 * snapshot, and the {@code OrderPlaced} outbox row atomically. Deliberately a separate bean so the
 * orchestrating saga never holds a transaction open across the remote reservation call.
 */
@Service
public class OrderPersistenceService {

  private final OrderRepository orderRepository;
  private final OutboxService outboxService;

  public OrderPersistenceService(OrderRepository orderRepository, OutboxService outboxService) {
    this.orderRepository = orderRepository;
    this.outboxService = outboxService;
  }

  /**
   * Persist a PENDING order from the authoritative reservation response. Line prices come from the
   * reservation, never from the client or the cart snapshot.
   */
  @Transactional
  public OrderEntity persistPlacedOrder(
      UUID orderId, Long userId, String idempotencyKey, ReservationResponse reservation) {
    BigDecimal subtotal = reservation.subtotal();
    OrderEntity order =
        new OrderEntity(
            orderId,
            userId,
            OrderStatus.PENDING,
            reservation.currency(),
            subtotal,
            // Wave 2: total == subtotal (no tax/shipping yet).
            subtotal,
            idempotencyKey);

    for (ReservationResponse.Item item : reservation.items()) {
      order.addItem(
          new OrderItem(
              item.productId(), item.name(), item.unitPrice(), item.quantity(), item.lineTotal()));
    }

    OrderEntity saved = orderRepository.save(order);
    outboxService.recordOrderPlaced(saved, Instant.now());
    return saved;
  }

  @Transactional
  public void markCancelled(OrderEntity order) {
    order.setStatus(OrderStatus.CANCELLED);
    orderRepository.save(order);
  }
}
