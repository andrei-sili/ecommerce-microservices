package com.ecommerce.order.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the broker topology owned by Order service and configures the listener container factory
 * with manual ack mode. All declarations are idempotent (declare-if-absent).
 */
@Configuration
public class RabbitMQConfig {

  /** Durable topic exchange shared by all services. Routing key drives fan-out. */
  public static final String EXCHANGE = "ecommerce.events";

  /** Dead-letter exchange. Messages nacked without requeue are routed here. */
  public static final String DLX = "ecommerce.events.dlx";

  /** Queue for payment events consumed by Order. Bound to {@code payment.*}. */
  public static final String PAYMENT_QUEUE = "order.payment-events";

  /** Dead-letter queue for unprocessable payment events. */
  public static final String PAYMENT_DLQ = "order.payment-events.dlq";

  // ---- Exchange declarations ----

  @Bean
  public TopicExchange eventExchange() {
    return new TopicExchange(EXCHANGE, true, false);
  }

  @Bean
  public TopicExchange deadLetterExchange() {
    return new TopicExchange(DLX, true, false);
  }

  // ---- Queue declarations ----

  @Bean
  public Queue paymentEventsQueue() {
    return QueueBuilder.durable(PAYMENT_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX)
        .withArgument("x-dead-letter-routing-key", PAYMENT_DLQ)
        .build();
  }

  @Bean
  public Queue paymentEventsDlq() {
    return QueueBuilder.durable(PAYMENT_DLQ).build();
  }

  // ---- Bindings ----

  @Bean
  public Binding paymentEventsBinding(Queue paymentEventsQueue, TopicExchange eventExchange) {
    return BindingBuilder.bind(paymentEventsQueue).to(eventExchange).with("payment.*");
  }

  @Bean
  public Binding paymentEventsDlqBinding(Queue paymentEventsDlq, TopicExchange deadLetterExchange) {
    return BindingBuilder.bind(paymentEventsDlq).to(deadLetterExchange).with(PAYMENT_DLQ);
  }

  // ---- Container factory ----

  /**
   * Manual ack mode: the consumer calls {@code channel.basicAck/Nack} explicitly, ensuring the
   * message is acked only after the side-effect transaction and inbox-dedup row commit.
   */
  @Bean
  public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
      ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(
        org.springframework.amqp.core.AcknowledgeMode.MANUAL);
    return factory;
  }
}
