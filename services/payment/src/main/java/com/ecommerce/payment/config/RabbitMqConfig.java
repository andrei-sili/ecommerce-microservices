package com.ecommerce.payment.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Payment declares only the producer-side exchange. Consumer queues (order.payment-events,
 * notification.payment-events) are owned by their respective services or pre-declared by devops.
 */
@Configuration
public class RabbitMqConfig {

  /** Durable topic exchange that carries all domain events for this platform. */
  @Bean
  public TopicExchange ecommerceEventsExchange() {
    return new TopicExchange("ecommerce.events", true, false);
  }

  /** Message converter — serialises to/from JSON consistently for the relay. */
  @Bean
  public Jackson2JsonMessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  /**
   * Override the auto-configured RabbitTemplate to add a return callback that logs unroutable
   * messages. Mandatory mode + publisher-confirm-type: correlated is already set via application
   * properties.
   */
  @Bean
  public RabbitTemplate rabbitTemplate(
      ConnectionFactory connectionFactory, Jackson2JsonMessageConverter messageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(messageConverter);
    template.setMandatory(true);
    template.setReturnsCallback(
        returned ->
            org.slf4j.LoggerFactory.getLogger(RabbitMqConfig.class)
                .warn(
                    "Message returned unrouted: exchange={} routingKey={} replyCode={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode()));
    return template;
  }
}
