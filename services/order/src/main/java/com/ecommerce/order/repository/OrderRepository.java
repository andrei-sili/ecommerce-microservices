package com.ecommerce.order.repository;

import com.ecommerce.order.model.OrderEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

  @EntityGraph(attributePaths = "items")
  Page<OrderEntity> findByUserId(Long userId, Pageable pageable);

  @EntityGraph(attributePaths = "items")
  Optional<OrderEntity> findWithItemsById(UUID id);

  Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);
}
