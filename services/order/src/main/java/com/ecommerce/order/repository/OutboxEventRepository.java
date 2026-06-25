package com.ecommerce.order.repository;

import com.ecommerce.order.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {}
