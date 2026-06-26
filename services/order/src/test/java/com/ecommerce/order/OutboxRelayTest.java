package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ecommerce.order.event.OutboxRelay;
import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Verifies the outbox relay drainer: unpublished rows are published and marked. */
class OutboxRelayTest extends AbstractIntegrationTest {

  @Autowired private OutboxRelay outboxRelay;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private OrderRepository orderRepository;

  // Mock so no real AMQP connection is attempted; lets us capture what was published.
  @MockBean private RabbitTemplate rabbitTemplate;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
  }

  @Test
  void drain_publishesUnpublishedRow_marksPublishedAt_correctRoutingKey() {
    OutboxEvent event =
        outboxEventRepository.save(
            new OutboxEvent(
                "Order",
                "some-order-id",
                "OrderPlaced",
                "{\"orderId\":\"some-order-id\",\"userId\":1,\"items\":[],\"total\":9.99,"
                    + "\"currency\":\"EUR\",\"occurredAt\":\"2026-06-26T10:00:00Z\"}",
                Instant.now()));
    assertThat(event.getPublishedAt()).isNull();

    outboxRelay.drain();

    // Capture via Object captor to disambiguate (String,String,Object) vs (String,Object,MPP).
    ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> rkCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> msgCaptor = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(rabbitTemplate)
        .convertAndSend(exchangeCaptor.capture(), rkCaptor.capture(), msgCaptor.capture());

    assertThat(exchangeCaptor.getValue()).isEqualTo("ecommerce.events");
    assertThat(rkCaptor.getValue()).isEqualTo("order.placed");

    Object published = msgCaptor.getValue();
    assertThat(published).isInstanceOf(Message.class);
    Message amqpMsg = (Message) published;
    assertThat(amqpMsg.getMessageProperties().getType()).isEqualTo("OrderPlaced");
    assertThat(amqpMsg.getMessageProperties().getContentType()).isEqualTo("application/json");
    assertThat(new String(amqpMsg.getBody())).contains("orderId");

    // DB row must have published_at set after relay.
    OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
    assertThat(reloaded.getPublishedAt()).isNotNull();
  }

  @Test
  void drain_skipsAlreadyPublished_doesNotPublish() {
    OutboxEvent event =
        outboxEventRepository.save(
            new OutboxEvent("Order", "x", "OrderPlaced", "{}", Instant.now()));
    event.setPublishedAt(Instant.now().minusSeconds(60));
    outboxEventRepository.save(event);

    outboxRelay.drain();

    verifyNoInteractions(rabbitTemplate);
  }

  @Test
  void drain_skipsUnknownEventType_doesNotPublish() {
    outboxEventRepository.save(
        new OutboxEvent("Order", "x", "UnknownEventType", "{}", Instant.now()));

    outboxRelay.drain();

    verifyNoInteractions(rabbitTemplate);
  }

  @Test
  void drain_multiplePendingRows_publishesAll() {
    outboxEventRepository.save(
        new OutboxEvent("Order", "a", "OrderPlaced", "{\"orderId\":\"a\"}", Instant.now()));
    outboxEventRepository.save(
        new OutboxEvent("Order", "b", "OrderPlaced", "{\"orderId\":\"b\"}", Instant.now()));

    outboxRelay.drain();

    assertThat(outboxEventRepository.findAll()).allMatch(e -> e.getPublishedAt() != null);
    // Disambiguate via Object captor: Object is not assignable to MessagePostProcessor,
    // so the compiler selects convertAndSend(String, String, Object) unambiguously.
    ArgumentCaptor<Object> msgCaptor = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(rabbitTemplate, times(2))
        .convertAndSend(Mockito.anyString(), Mockito.anyString(), msgCaptor.capture());
  }
}
