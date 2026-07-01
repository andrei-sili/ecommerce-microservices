package com.ecommerce.payment.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Payment declares only the producer-side exchange. Consumer queues (order.payment-events,
 * notification.payment-events) are owned by their respective services or pre-declared by devops.
 *
 * <p>The {@code RabbitTemplate} is intentionally NOT customised here — it is Boot's auto-configured
 * template. Driven by {@code spring.rabbitmq.publisher-confirm-type: correlated} and {@code
 * publisher-returns: true}, it publishes {@code mandatory} with correlated publisher confirms and
 * populates {@link org.springframework.amqp.rabbit.connection.CorrelationData#getReturned()} on a
 * {@code basic.return}. A previous custom template registered a log-only returns-callback; combined
 * with {@code waitForConfirmsOrDie} (which does not throw on an unroutable message) the relay
 * marked every row published — including silently-dropped ones (event loss). The relay now reads
 * the per-publish confirm + return via {@code CorrelationData}; see {@code OutboxRelay}.
 */
@Configuration
public class RabbitMqConfig {

  /** Durable topic exchange that carries all domain events for this platform. */
  @Bean
  public TopicExchange ecommerceEventsExchange() {
    return new TopicExchange("ecommerce.events", true, false);
  }
}
