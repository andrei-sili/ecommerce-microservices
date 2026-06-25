package com.ecommerce.order.repository;

import com.ecommerce.order.model.OrderEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

  /**
   * Page the order rows in SQL with NO collection fetch — {@code items} is LAZY, so the page/count
   * queries stay row-only and Hibernate applies {@code LIMIT/OFFSET} in the database (no in-memory
   * pagination, no HHH90003004). Items are batch-loaded separately via {@link #fetchItems}.
   */
  Page<OrderEntity> findByUserId(Long userId, Pageable pageable);

  /**
   * Initialize the {@code items} collection for an already-loaded page of orders in one extra query
   * ({@code WHERE id IN (...)}). Returned within the same persistence context, so the page entities
   * get their collections populated; the assembled order here is incidental.
   */
  @Query("select distinct o from OrderEntity o left join fetch o.items where o.id in :ids")
  List<OrderEntity> fetchItems(@Param("ids") List<UUID> ids);

  @EntityGraph(attributePaths = "items")
  Optional<OrderEntity> findWithItemsById(UUID id);

  Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);
}
