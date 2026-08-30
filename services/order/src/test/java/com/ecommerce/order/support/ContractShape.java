package com.ecommerce.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Wire-shape assertions shared by the contract pins. Key sets are asserted EXACTLY (never as a
 * subset) because the regression these guard against is a body <em>gaining</em> a key: if
 * {@code @JsonInclude(NON_NULL)} ever stops being honoured, {@code product_id:null} appears in the
 * order 401 and a subset assertion would still pass.
 */
public final class ContractShape {

  /**
   * ISO-8601 UTC instant, second or sub-second precision. A JSON Number (epoch seconds) fails this,
   * which is the point: the representation is contract, not a serializer default.
   */
  public static final String ISO_8601_UTC =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

  private ContractShape() {}

  /** Field names of a JSON object, in document order. */
  public static Set<String> keysOf(JsonNode object) {
    return new LinkedHashSet<>(object.propertyNames());
  }

  /** Asserts the field is a JSON String holding an ISO-8601 UTC instant. */
  public static void assertIso8601Utc(JsonNode object, String field) {
    JsonNode value = object.get(field);
    assertThat(value).as("'%s' must be present", field).isNotNull();
    assertThat(value.isTextual())
        .as("'%s' must be a JSON String, was %s", field, value.getNodeType())
        .isTrue();
    assertThat(value.asText()).as("'%s' must be ISO-8601 UTC", field).matches(ISO_8601_UTC);
  }
}
