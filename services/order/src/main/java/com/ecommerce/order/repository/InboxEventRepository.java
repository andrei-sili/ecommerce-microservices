package com.ecommerce.order.repository;

import com.ecommerce.order.model.InboxEvent;
import com.ecommerce.order.model.InboxEventId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, InboxEventId> {}
