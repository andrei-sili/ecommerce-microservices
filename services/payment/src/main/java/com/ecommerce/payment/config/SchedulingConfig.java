package com.ecommerce.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's scheduling support (the outbox relay). Conditional so integration tests can
 * set {@code app.scheduling.enabled=false} to keep the relay from auto-firing, and instead drive
 * {@code OutboxRelay.drain()} directly for deterministic assertions.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
