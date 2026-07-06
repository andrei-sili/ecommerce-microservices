package com.ecommerce.order;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ecommerce.order.event.OutboxRelay;
import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Fast, broker-free checks for the relay's skip branches (no publish is attempted, so a mocked
 * {@link RabbitTemplate} is sufficient). The publish + publisher-confirm + "unroutable is not
 * marked published" behaviour is covered against a REAL broker in {@link
 * OutboxRelayUnroutableIntegrationTest} — a mock cannot prove a cross-service seam (see {@code
 * testing.md}).
 */
class OutboxRelayTest extends AbstractIntegrationTest {

  @Autowired private OutboxRelay outboxRelay;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private OrderRepository orderRepository;

  // Mocked so no real AMQP connection is attempted; these paths must never touch the broker.
  @MockitoBean private RabbitTemplate rabbitTemplate;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
  }

  @Test
  void drain_skipsAlreadyPublished_doesNotPublish() {
    OutboxEvent event =
        outboxEventRepository.save(
            new OutboxEvent("Order", "x", "OrderPlaced", "{}", Instant.now()));
    event.setPublishedAt(Instant.now().minusSeconds(60));
    outboxEventRepository.save(event);

    // Clear one-time context-init interactions (RabbitMessagingTemplate touches the template on
    // afterPropertiesSet) so the assertion isolates what drain() itself does to the broker.
    clearInvocations(rabbitTemplate);
    outboxRelay.drain();

    verifyNoInteractions(rabbitTemplate);
  }

  @Test
  void drain_skipsUnknownEventType_doesNotPublish() {
    outboxEventRepository.save(
        new OutboxEvent("Order", "x", "UnknownEventType", "{}", Instant.now()));

    clearInvocations(rabbitTemplate);
    outboxRelay.drain();

    verifyNoInteractions(rabbitTemplate);
  }
}
