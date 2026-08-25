package com.ecommerce.product.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Wire-shape assertions: the exact key set of a JSON object and the format of its date fields.
 *
 * <p>Both are contract surface that a serializer change can move silently. {@link
 * #assertKeysExactly} fails on a MISSING key and on an EXTRA one — an inclusion default that stops
 * omitting nulls shows up as an added key, which a subset-style assertion would happily pass.
 */
public final class JsonShape {

  /**
   * ISO-8601 instant in UTC — the only accepted rendering of a date on the wire. An epoch number
   * (the fallback when date-as-timestamp handling flips) cannot match, and neither can an offset
   * other than {@code Z}.
   */
  private static final Pattern ISO_8601_UTC =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$");

  private JsonShape() {}

  public static List<String> keysOf(JsonNode object) {
    assertThat(object).isNotNull();
    assertThat(object.isObject()).as("expected a JSON object, was: %s", object).isTrue();
    List<String> keys = new ArrayList<>();
    object.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  public static void assertKeysExactly(JsonNode object, String... expected) {
    assertThat(keysOf(object))
        .as("exact key set of %s", object)
        .containsExactlyInAnyOrder(expected);
  }

  public static void assertIso8601Utc(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as("field %s is absent from %s", field, object).isNotNull();
    assertThat(value.isTextual())
        .as("field %s must be a JSON string, was: %s", field, value)
        .isTrue();
    assertThat(value.textValue()).as("field %s", field).matches(ISO_8601_UTC);
  }
}
