package com.ecommerce.order.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Single real RabbitMQ broker shared by the suites that need one. Started on first use and reused
 * across contexts; started manually (never via {@code @Testcontainers}) so an unavailable Docker
 * fails loudly instead of self-skipping into a green "Tests run: 0".
 */
public final class RabbitTestBroker {

  private static final RabbitMQContainer CONTAINER =
      new RabbitMQContainer(
          DockerImageName.parse("rabbitmq:3-management-alpine")
              .asCompatibleSubstituteFor("rabbitmq"));

  private RabbitTestBroker() {}

  public static RabbitMQContainer start() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
    return CONTAINER;
  }

  /** Points the context at the shared broker; suppliers resolve lazily, after {@link #start()}. */
  public static void registerConnection(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", CONTAINER::getHost);
    registry.add("spring.rabbitmq.port", CONTAINER::getAmqpPort);
    registry.add("spring.rabbitmq.username", CONTAINER::getAdminUsername);
    registry.add("spring.rabbitmq.password", CONTAINER::getAdminPassword);
  }
}
