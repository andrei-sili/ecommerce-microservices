package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.event.OutboxService;
import com.ecommerce.payment.model.OutboxEvent;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.repository.OutboxEventRepository;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * AC-0.5 — the {@code PaymentCompleted} golden fixture, produced by the REAL {@link OutboxService}
 * against a fixed instant (the "fixed clock": {@code occurredAt} is a parameter, so pinning it pins
 * the only non-deterministic input) and a payment whose ids are fixed.
 *
 * <p><b>Two different byte strings exist and both are pinned, because they are not the same
 * string.</b> {@code outbox_events.payload} is a Postgres {@code jsonb} column, and Postgres
 * rewrites JSON on insert: keys are reordered by (length, then bytes) and a space is inserted after
 * every {@code :} and {@code ,}. The relay ships that column verbatim ({@code OutboxRelay}), so:
 *
 * <ul>
 *   <li>the <b>wire</b> bytes — what {@code order}'s {@code PaymentEventConsumer.EVENT_MAPPER}
 *       actually parses, and what B17's {@code payload::text} baseline captures — are the
 *       normalized ones, pinned here against {@code golden/payment-completed.json};
 *   <li>the <b>serializer</b> bytes — what Jackson emitted before Postgres touched them — exist
 *       only inside the JVM, and are pinned separately below. Without that second pin a Jackson
 *       key-order or whitespace change would be invisible: Postgres launders it.
 * </ul>
 *
 * <p>Consequence worth stating once: A4's "Jackson 3 enables SORT_PROPERTIES_ALPHABETICALLY" cannot
 * reach an event payload on the wire — Postgres already reorders it, and by length, not
 * alphabetically. That risk is real for REST bodies only.
 */
class GoldenOutboxPayloadIntegrationTest extends AbstractIntegrationTest {

  /** The instant B16 pins for the payment→order round trip. */
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-26T10:00:00Z");

  private static final UUID PAYMENT_ID = UUID.fromString("b1e70000-1111-2222-3333-444455556666");
  private static final UUID ORDER_ID = UUID.fromString("9f1c2e7a-1111-2222-3333-444455556666");

  /**
   * Jackson's own output: camelCase, record-declaration order, no spaces, {@code occurredAt} as an
   * ISO-8601 string and {@code amount} as an unquoted number. Every one of those five properties
   * rests on {@code OutboxService}'s hand-built {@code JsonMapper} and none of them survives a
   * silent default change.
   *
   * <p><b>This is a canary, NOT a wire contract.</b> No consumer ever sees these bytes. If it ever
   * disagrees with {@code golden/payment-completed.json}, the fixture is right and this string is
   * what moved — reconcile in that direction only, and never "fix" the fixture to match here.
   */
  private static final String EXPECTED_SERIALIZER_BYTES =
      "{\"paymentId\":\"b1e70000-1111-2222-3333-444455556666\","
          + "\"orderId\":\"9f1c2e7a-1111-2222-3333-444455556666\","
          + "\"userId\":7,\"amount\":39.98,\"currency\":\"EUR\",\"status\":\"SUCCEEDED\","
          + "\"occurredAt\":\"2026-06-26T10:00:00Z\"}";

  @Autowired private OutboxService outboxService;
  @Autowired private OutboxEventRepository outboxEventRepository;

  // Spied, not mocked: the row still reaches Postgres (so the wire assertion below is real), and
  // the argument capture is the only place Jackson's pre-jsonb bytes are observable.
  @MockitoSpyBean private OutboxEventRepository spiedRepository;

  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
  }

  @Test
  void paymentCompleted_wireBytes_equalTheGoldenFixture() throws Exception {
    outboxService.recordPaymentCompleted(succeededPayment(), OCCURRED_AT);

    OutboxEvent row = outboxEventRepository.findAll().get(0);

    assertThat(row.getEventType()).isEqualTo("PaymentCompleted");
    assertThat(row.getOccurredAt()).isEqualTo(OCCURRED_AT);
    assertThat(row.getPayload())
        .as("the bytes the relay ships must equal golden/payment-completed.json exactly")
        .isEqualTo(goldenFixture());
  }

  @Test
  void paymentCompleted_serializerBytes_areCamelCaseIsoAndUnquotedNumbers() throws Exception {
    outboxService.recordPaymentCompleted(succeededPayment(), OCCURRED_AT);

    ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(spiedRepository).save(saved.capture());

    assertThat(saved.getValue().getPayload())
        .as("Jackson's output, before Postgres jsonb normalizes it")
        .isEqualTo(EXPECTED_SERIALIZER_BYTES);
  }

  /**
   * Reads the committed fixture. {@code strip()} removes only the trailing newline every editor and
   * git's own {@code core.autocrlf} handling add to a text file — the JSON text itself is compared
   * byte for byte.
   */
  private static String goldenFixture() throws IOException {
    return new String(
            new ClassPathResource("golden/payment-completed.json").getContentAsByteArray(),
            StandardCharsets.UTF_8)
        .strip();
  }

  /**
   * A SUCCEEDED payment with fixed ids. The id is normally assigned by Hibernate on persist, so it
   * is set directly here — the fixture must not move between runs.
   */
  private static Payment succeededPayment() throws ReflectiveOperationException {
    Payment payment =
        new Payment(
            ORDER_ID,
            7L,
            new BigDecimal("39.98"),
            "EUR",
            PaymentStatus.SUCCEEDED,
            "sandbox",
            "pm_valid_token",
            "key-golden");
    Field id = Payment.class.getDeclaredField("id");
    id.setAccessible(true);
    id.set(payment, PAYMENT_ID);
    return payment;
  }
}
