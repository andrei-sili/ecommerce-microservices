package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
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
 * AC-0.5 and AC-5.7 — the golden fixtures for all three payload types, each produced by the REAL
 * {@link OutboxService} against a fixed instant (the "fixed clock": {@code occurredAt} is a
 * parameter, so pinning it pins the only non-deterministic input) and payments whose ids are fixed.
 * Each type is compared against its OWN fixture: {@code PaymentFailed} carries an eighth key the
 * other two must never grow, so one shared baseline would be a comparison across shapes.
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
 * <p><b>Consequence, and the limit of what a green run here proves.</b> A4's "Jackson 3 enables
 * SORT_PROPERTIES_ALPHABETICALLY" cannot reach an event payload on the wire — Postgres already
 * reordered it, and by length, not alphabetically. So a passing WIRE assertion is evidence about
 * casing, key presence, number shape and date format, and about <b>nothing else</b>; it is blind to
 * Jackson's key order by construction. Do not cite one as ordering evidence.
 *
 * <p>The gap is closed rather than merely recorded: the serializer canaries below see the order
 * Jackson actually emitted, and at 4.1.1 all three are unchanged. The reason is measurable rather
 * than lucky — all three payload types are {@code record}s, so every property is a creator property
 * and {@code SORT_CREATOR_PROPERTIES_FIRST} keeps declaration order ahead of the alphabetical sort.
 * payment's RESPONSE DTOs are getter POJOs with no creator properties, which is why those DO
 * reorder (A18). The two surfaces are different and must not be conflated: A18 is observable on the
 * HTTP bodies, never on these rows.
 */
class GoldenOutboxPayloadIntegrationTest extends AbstractIntegrationTest {

  /** The instant B16 pins for the payment→order round trip. */
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-26T10:00:00Z");

  private static final UUID PAYMENT_ID = UUID.fromString("b1e70000-1111-2222-3333-444455556666");
  private static final UUID ORDER_ID = UUID.fromString("9f1c2e7a-1111-2222-3333-444455556666");
  private static final UUID FAILED_PAYMENT_ID =
      UUID.fromString("c2f80000-1111-2222-3333-444455556666");
  private static final UUID CANCELLED_PAYMENT_ID =
      UUID.fromString("d3090000-1111-2222-3333-444455556666");

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

  /**
   * The same canary for {@code PaymentFailed}: record-declaration order, and {@code failureReason}
   * sitting where the record declares it rather than where an alphabetical sort would put it.
   */
  private static final String EXPECTED_FAILED_SERIALIZER_BYTES =
      "{\"paymentId\":\"c2f80000-1111-2222-3333-444455556666\","
          + "\"orderId\":\"9f1c2e7a-1111-2222-3333-444455556666\","
          + "\"userId\":7,\"amount\":29.50,\"currency\":\"EUR\",\"status\":\"FAILED\","
          + "\"failureReason\":\"CARD_DECLINED\",\"occurredAt\":\"2026-06-26T10:00:00Z\"}";

  /** And for {@code PaymentCancelled}, whose record declares the same seven components. */
  private static final String EXPECTED_CANCELLED_SERIALIZER_BYTES =
      "{\"paymentId\":\"d3090000-1111-2222-3333-444455556666\","
          + "\"orderId\":\"9f1c2e7a-1111-2222-3333-444455556666\","
          + "\"userId\":7,\"amount\":9.95,\"currency\":\"EUR\",\"status\":\"CANCELLED\","
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
   * AC-5.7's other two payload shapes, each against its OWN fixture. The three types are not
   * interchangeable — {@code PaymentFailed} carries an eighth key ({@code failureReason}) that the
   * other two must never grow, and comparing any of them against {@code PaymentCompleted}'s
   * baseline would be comparing a shape to a different shape.
   *
   * <p>Both fixtures were captured FROM THE COLUMN, never from the serializer. The tell is visible
   * in the files themselves: keys ordered by (length, then bytes) and a space after every {@code :}
   * is Postgres's {@code jsonb} normalization. A fixture sitting in record-declaration order with
   * no spaces would be serializer output pasted into the wrong place, and would not match these
   * bytes.
   */
  @Test
  void paymentFailed_wireBytes_equalItsOwnGoldenFixture() throws Exception {
    outboxService.recordPaymentFailed(failedPayment(), OCCURRED_AT);

    OutboxEvent row = outboxEventRepository.findAll().get(0);

    assertThat(row.getEventType()).isEqualTo("PaymentFailed");
    assertThat(row.getPayload())
        .as("the bytes the relay ships must equal golden/payment-failed.json exactly")
        .isEqualTo(goldenFixture("payment-failed.json"));
  }

  @Test
  void paymentCancelled_wireBytes_equalItsOwnGoldenFixture() throws Exception {
    outboxService.recordPaymentCancelled(cancelledPayment(), OCCURRED_AT);

    OutboxEvent row = outboxEventRepository.findAll().get(0);

    assertThat(row.getEventType()).isEqualTo("PaymentCancelled");
    assertThat(row.getPayload())
        .as("the bytes the relay ships must equal golden/payment-cancelled.json exactly")
        .isEqualTo(goldenFixture("payment-cancelled.json"));
  }

  /**
   * The Jackson-ordering canary for the other two types. Only these pre-{@code jsonb} bytes can see
   * key order at all, so without them AC-5.7 would rest entirely on column reads that Postgres has
   * already reordered — green, and blind to exactly the property this wave moves.
   *
   * <p>What the three canaries jointly establish, measured rather than argued: all three payload
   * types are <b>records</b>, so every property is a creator property and Jackson 3's {@code
   * SORT_CREATOR_PROPERTIES_FIRST} keeps declaration order ahead of {@code
   * SORT_PROPERTIES_ALPHABETICALLY}. That is why these strings are unchanged at 4.1.1 while
   * payment's response DTOs — getter POJOs, no creator properties — do reorder (A18). Turn one of
   * these payloads into a class with getters and these three rows are what goes red.
   */
  @Test
  void failedAndCancelled_serializerBytes_keepRecordDeclarationOrder() throws Exception {
    outboxService.recordPaymentFailed(failedPayment(), OCCURRED_AT);
    outboxService.recordPaymentCancelled(cancelledPayment(), OCCURRED_AT);

    ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(spiedRepository, times(2)).save(saved.capture());

    assertThat(saved.getAllValues().get(0).getPayload())
        .as("PaymentFailed, as Jackson emitted it — before Postgres jsonb normalizes it")
        .isEqualTo(EXPECTED_FAILED_SERIALIZER_BYTES);
    assertThat(saved.getAllValues().get(1).getPayload())
        .as("PaymentCancelled, as Jackson emitted it")
        .isEqualTo(EXPECTED_CANCELLED_SERIALIZER_BYTES);
  }

  /**
   * Reads a committed fixture. {@code strip()} removes only the trailing newline every editor and
   * git's own {@code core.autocrlf} handling add to a text file — the JSON text itself is compared
   * byte for byte.
   */
  private static String goldenFixture(String name) throws IOException {
    return new String(
            new ClassPathResource("golden/" + name).getContentAsByteArray(), StandardCharsets.UTF_8)
        .strip();
  }

  private static String goldenFixture() throws IOException {
    return goldenFixture("payment-completed.json");
  }

  private static Payment failedPayment() throws ReflectiveOperationException {
    Payment payment =
        new Payment(
            ORDER_ID,
            7L,
            new BigDecimal("29.50"),
            "EUR",
            PaymentStatus.FAILED,
            "sandbox",
            "pm_decline_this",
            "key-golden-failed");
    payment.setFailureReason("CARD_DECLINED");
    setId(payment, FAILED_PAYMENT_ID);
    return payment;
  }

  private static Payment cancelledPayment() throws ReflectiveOperationException {
    Payment payment =
        new Payment(
            ORDER_ID,
            7L,
            new BigDecimal("9.95"),
            "EUR",
            PaymentStatus.CANCELLED,
            "sandbox",
            "pm_cancel_seed",
            "key-golden-cancelled");
    setId(payment, CANCELLED_PAYMENT_ID);
    return payment;
  }

  private static void setId(Payment payment, UUID value) throws ReflectiveOperationException {
    Field id = Payment.class.getDeclaredField("id");
    id.setAccessible(true);
    id.set(payment, value);
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
