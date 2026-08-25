package com.ecommerce.payment.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shape assertions for the wire bodies: exact key sets and ISO-8601 date fields.
 *
 * <p>Reads through JsonPath rather than through the application's own {@code ObjectMapper} — a pin
 * must not be rendered blind by the very mapper whose behaviour it exists to pin.
 */
public final class JsonShape {

  /** Contract format for every date on the wire: ISO-8601, UTC, {@code Z}-suffixed. */
  private static final Pattern ISO_8601_UTC =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

  private JsonShape() {}

  /** Top-level key set of a JSON object body. */
  public static Set<String> keysOf(String json) {
    Map<String, Object> object = JsonPath.read(json, "$");
    return object.keySet();
  }

  /**
   * Asserts {@code field} is a JSON <b>number</b> of the expected value — never a quoted string.
   * The counterpart of {@link #assertIso8601Utc}: {@code WRITE_NUMBERS_AS_STRINGS} would turn every
   * amount into {@code "39.98"}, which still renders fine in a log and breaks every consumer's
   * arithmetic. Asserted through the parsed value rather than the raw text because an outbox
   * payload read back from a {@code jsonb} column carries Postgres's own spacing.
   */
  public static void assertJsonNumber(String json, String field, double expected) {
    Object value = JsonPath.read(json, "$." + field);
    assertThat(value)
        .as("%s must be a JSON number, not a quoted string", field)
        .isInstanceOf(Number.class);
    assertThat(((Number) value).doubleValue()).as("%s value", field).isEqualTo(expected);
  }

  /**
   * Asserts {@code field} is a JSON <b>String</b> holding an ISO-8601 UTC instant. The type check
   * is load-bearing: an epoch-number flip still reads as "a date" to a human while breaking every
   * consumer, and it is exactly what dropping {@code WRITE_DATES_AS_TIMESTAMPS} would produce.
   */
  public static void assertIso8601Utc(String json, String field) {
    Object value = JsonPath.read(json, "$." + field);
    assertThat(value)
        .as("%s must be a JSON string, not a number", field)
        .isInstanceOf(String.class);
    String text = (String) value;
    assertThat(text).as("%s must be ISO-8601 UTC", field).matches(ISO_8601_UTC);
    assertThatCode(() -> Instant.parse(text)).doesNotThrowAnyException();
  }
}
