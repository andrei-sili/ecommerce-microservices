package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * C13 / AC-5.10, second half: the relay's two skip branches never touch the broker and never mutate
 * a row.
 *
 * <p>order has had these two rows since S1; payment has the identical branches — the empty-batch
 * early return ({@code OutboxRelay:73-75}) and the unmapped {@code event_type} guard ({@code
 * :79-86}) — with no test at all, which is the "Named gap" C13 records. They are cheap and they are
 * not cosmetic: an unmapped type must stay {@code published_at IS NULL} FOREVER rather than being
 * marked published on the way past, because marking it would silently discard a domain event that a
 * later release teaches the relay to route.
 *
 * <p>Deliberately a plain unit test with a mocked {@link RabbitTemplate}. What these branches
 * promise is the ABSENCE of a broker interaction, and {@code verifyNoInteractions} states that
 * directly; a broker-backed variant would have to prove a negative by waiting. The
 * routing/confirm/return behaviour these branches sit next to is covered against a real broker by
 * {@link OutboxRelayMisCorrelationIntegrationTest}, {@link OutboxRelayUnroutableIntegrationTest}
 * and {@link OutboxRelayBatchIntegrationTest} — so mocking here does not hide a seam.
 */
class OutboxRelaySkipBranchesTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-06-26T10:00:00Z");

  private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final OutboxRelay relay = new OutboxRelay(repository, rabbitTemplate, 50, 5000);

  @Test
  void emptyBatch_returnsEarly_withoutTouchingTheBroker() {
    when(repository.findUnpublishedWithLock(50)).thenReturn(List.of());

    relay.drain();

    verifyNoInteractions(rabbitTemplate);
    verify(repository).findUnpublishedWithLock(50);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void unmappedEventType_isSkipped_neverPublishedAndNeverMarked() {
    OutboxEvent unknown = row(1L, "PaymentRefunded");
    when(repository.findUnpublishedWithLock(50)).thenReturn(List.of(unknown));

    relay.drain();

    verifyNoInteractions(rabbitTemplate);
    assertThat(unknown.getPublishedAt())
        .as("an unmapped event_type must stay NULL forever, never be marked on the way past")
        .isNull();
  }

  /**
   * The mixed batch is the row that matters. A skip implemented as {@code break} instead of {@code
   * continue} — or one that lets the unmapped row consume the routable row's confirm — would pass
   * both single-row cases above and silently stop draining at the first unknown type. So the
   * unmapped row is placed FIRST, and the mapped row after it.
   */
  @Test
  void mixedBatch_skipsTheUnmappedRow_andStillPublishesTheMappedOne() {
    OutboxEvent unknown = row(1L, "PaymentRefunded");
    OutboxEvent completed = row(2L, "PaymentCompleted");
    when(repository.findUnpublishedWithLock(50)).thenReturn(List.of(unknown, completed));
    ackOnSend();

    relay.drain();

    verify(rabbitTemplate)
        .send(eq("ecommerce.events"), eq("payment.completed"), any(Message.class), any());
    verifyNoMoreInteractions(rabbitTemplate);
    assertThat(unknown.getPublishedAt()).as("the unmapped row is still untouched").isNull();
    assertThat(completed.getPublishedAt())
        .as("a skip must not stop the drain: the mapped row after it is still published")
        .isNotNull();
  }

  /**
   * Completes each publish's correlation future with an ack, as a healthy broker would. {@code
   * RabbitTemplate.send} is {@code void}, so this is a {@code doAnswer}, not a {@code when}.
   */
  private void ackOnSend() {
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(3);
              CompletableFuture<CorrelationData.Confirm> future = correlation.getFuture();
              future.complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbitTemplate)
        .send(any(), any(), any(Message.class), any(CorrelationData.class));
  }

  private static OutboxEvent row(long id, String eventType) {
    OutboxEvent event =
        new OutboxEvent("Payment", "agg-" + id, eventType, "{\"paymentId\":\"x\"}", OCCURRED_AT);
    org.springframework.test.util.ReflectionTestUtils.setField(event, "id", id);
    return event;
  }
}
