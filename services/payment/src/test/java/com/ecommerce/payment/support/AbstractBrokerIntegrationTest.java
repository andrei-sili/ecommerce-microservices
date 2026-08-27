package com.ecommerce.payment.support;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Extends the Postgres-backed base with a REAL RabbitMQ broker (Testcontainers) so the outbox relay
 * is exercised with ZERO broker mocks. Concrete subclasses do NOT {@code @MockitoBean OutboxRelay}
 * — they drive the real relay against the real broker and assert row state + delivery.
 *
 * <p>{@code publisher-confirm-type: correlated} + {@code publisher-returns: true} are sourced from
 * the SHIPPED {@code application.yml} — the test overlay deliberately does not repeat them, so
 * deleting either one from production config turns the seam tests RED (C5). They used to be
 * mirrored in the test yml, which meant the seam tests protected a copy rather than the shipped
 * setting. Only container wiring and {@code dynamic=true} (so RabbitAdmin declares the exchange)
 * are overridden here.
 */
public abstract class AbstractBrokerIntegrationTest extends AbstractIntegrationTest {

  protected static final String EXCHANGE = "ecommerce.events";

  /**
   * Every durable queue any broker seam test may declare. Deleted before each test so a queue left
   * bound by a prior test cannot catch (and wrongly route) another test's "must be unroutable"
   * publish — the RED precondition must hold regardless of test ordering.
   */
  private static final List<String> SEAM_QUEUES =
      List.of("notification.payment-events", "test.payment-completed", "test.payment-failed");

  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(
          DockerImageName.parse("rabbitmq:3-management-alpine")
              .asCompatibleSubstituteFor("rabbitmq"));

  @BeforeAll
  static void startBroker() {
    if (!RABBIT.isRunning()) {
      RABBIT.start();
    }
  }

  @DynamicPropertySource
  static void brokerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    // Re-enable RabbitAdmin (the test overlay sets dynamic=false) so it declares ecommerce.events.
    registry.add("spring.rabbitmq.dynamic", () -> "true");
  }

  /** (Re)declares the exchange and removes all seam queues for a deterministic clean slate. */
  protected void resetSeamTopology(RabbitAdmin rabbitAdmin) {
    rabbitAdmin.initialize();
    SEAM_QUEUES.forEach(rabbitAdmin::deleteQueue);
  }
}
