package com.ecommerce.payment.repository;

import com.ecommerce.payment.model.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedWebhookEventRepository
    extends JpaRepository<ProcessedWebhookEvent, String> {}
