package com.ecommerce.order.repository;

import com.ecommerce.order.model.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

  /**
   * Fetch up to {@code limit} unpublished outbox rows under a {@code FOR UPDATE SKIP LOCKED}
   * advisory lock so concurrent relay instances never double-publish the same row.
   *
   * <p>Must be called inside an active transaction (the lock is released on commit/rollback).
   */
  @Query(
      value =
          "SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY id"
              + " FOR UPDATE SKIP LOCKED LIMIT :limit",
      nativeQuery = true)
  List<OutboxEvent> findUnpublishedBatch(@Param("limit") int limit);
}
