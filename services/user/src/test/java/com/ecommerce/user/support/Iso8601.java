package com.ecommerce.user.support;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Single source of truth for the wire form of every timestamp this service emits — REST envelope
 * and event payload alike — so both sides are held to the same contract regex.
 */
public final class Iso8601 {

  /** Contract form, e.g. {@code 2026-08-04T08:26:57.565240280Z}: UTC, fractional part optional. */
  public static final Pattern UTC_INSTANT =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

  private Iso8601() {}

  /**
   * Fails unless {@code field} is a JSON <em>string</em> in the contract form. The node-type half
   * is load-bearing: a serializer that starts writing epoch numbers still yields a perfectly usable
   * {@code asText()}, so a regex applied to {@code asText()} alone would report the flip as a
   * message about digits rather than about the type that actually changed.
   */
  public static void assertUtcInstant(JsonNode body, String field) {
    JsonNode node = body.get(field);
    assertNotNull(node, "missing date field: " + field);
    assertTrue(
        node.isTextual(),
        field + " must be a JSON string, was " + node.getNodeType() + ": " + node);
    assertTrue(
        UTC_INSTANT.matcher(node.textValue()).matches(),
        field + " must be ISO-8601 UTC (" + UTC_INSTANT.pattern() + "), was: " + node.textValue());
  }
}
