package com.ecommerce.payment.repository;

import com.ecommerce.payment.model.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

  /**
   * Selects unpublished rows in id order under a SKIP LOCKED row lock so multiple relay instances
   * never process the same row concurrently.
   */
  @Query(
      value =
          "SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY id"
              + " FOR UPDATE SKIP LOCKED LIMIT :limit",
      nativeQuery = true)
  List<OutboxEvent> findUnpublishedWithLock(@Param("limit") int limit);

  @Modifying
  @Query("UPDATE OutboxEvent o SET o.publishedAt = CURRENT_TIMESTAMP WHERE o.id IN :ids")
  void markPublished(@Param("ids") List<Long> ids);
}
