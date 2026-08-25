package com.ecommerce.payment.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.model.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * C4 — the delivered AMQP envelope, asserted whole.
 *
 * <p>Both broker seam tests used to assert only {@code body.contains(paymentId)}, which survives a
 * key reorder, a whitespace change, a dropped property and a re-serialization — every one of the
 * things a Jackson or Spring AMQP major bump can actually do. The body is compared for exact
 * equality against the {@code payload} column, which is what "the relay ships the column verbatim"
 * means; the five message properties are compared individually because a consumer routes and
 * de-duplicates on them.
 */
public final class WireEnvelope {

  private WireEnvelope() {}

  /**
   * @param row must be re-read from the DB after the drain. The entity returned by {@code save()}
   *     still holds the pre-insert string, while the relay ships the {@code jsonb} column, which
   *     Postgres has reordered and re-spaced — comparing against the wrong one is a false red.
   */
  public static void assertMatches(Message delivered, OutboxEvent row) {
    MessageProperties props = delivered.getMessageProperties();

    assertThat(new String(delivered.getBody(), StandardCharsets.UTF_8))
        .as("body bytes must equal the payload column verbatim -- no re-serialization")
        .isEqualTo(row.getPayload());
    assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    assertThat(props.getReceivedDeliveryMode())
        .as("events must be persistent or a broker restart loses them")
        .isEqualTo(MessageDeliveryMode.PERSISTENT);
    assertThat(props.getMessageId()).isEqualTo(String.valueOf(row.getId()));
    assertThat(props.getType()).isEqualTo(row.getEventType());
    assertThat(props.getTimestamp()).isEqualTo(Date.from(row.getOccurredAt()));
  }
}
