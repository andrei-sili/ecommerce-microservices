package com.ecommerce.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B9's bean-level half: the two {@code spring.jackson.*} properties Order's wire contract rests on
 * are asserted as BOUND STATE on the injected mapper, not inferred from a body that happens to look
 * right. Both claims are needed — every other casing assertion in this suite reads serialized
 * output, which is a different statement from "the property is still bound".
 *
 * <p>The distinction pays off at exactly one moment: a rename or re-scope on the framework side
 * leaves the key in the yml silently unbound. Every REST assertion in the service would then fail
 * at once, as a cascade of unrelated-looking body mismatches, and the single property that stopped
 * binding would have to be reconstructed from them. This class fails first and names it.
 *
 * <p>Unlike its counterpart on {@code user}, this one is not scope-limited: Order's test config is
 * a PROFILE OVERLAY that deliberately declares no {@code spring.jackson.*} key, so the values
 * asserted below can only have come from the shipped {@code src/main/resources/application.yml}.
 * That is the whole point — mutation M2 (drop {@code default-property-inclusion}, flip {@code
 * property-naming-strategy} to LOWER_CAMEL_CASE in the SHIPPED file) turns this class RED, and used
 * to leave the suite green.
 */
class JacksonBindingIntegrationTest extends AbstractIntegrationTest {

  private record Probe(String multiWordName, String absentWhenNull, Instant when) {}

  @Autowired private ObjectMapper objectMapper;

  @Test
  void bootMapper_usesSnakeCaseNaming() {
    assertEquals(
        PropertyNamingStrategies.SNAKE_CASE,
        objectMapper.getSerializationConfig().getPropertyNamingStrategy(),
        "spring.jackson.property-naming-strategy must still bind from the SHIPPED application.yml —"
            + " REST bodies are snake_case (event payloads stay camelCase, see"
            + " OutboxGoldenPayloadTest)");
  }

  @Test
  void bootMapper_suppressesNulls() {
    assertEquals(
        JsonInclude.Include.NON_NULL,
        objectMapper.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion(),
        "spring.jackson.default-property-inclusion must still bind from the SHIPPED"
            + " application.yml — it is the fleet-wide default that keeps nulls out of response"
            + " bodies. Note it is NOT what holds the error envelope at four keys: ErrorResponse"
            + " carries its own type-level @JsonInclude(NON_NULL), so the envelope survives this"
            + " property independently. Which is exactly why this bean-level row is needed — no"
            + " wire assertion in this service can see the property go missing.");
  }

  /**
   * The same two properties observed as behaviour, so the pin fails in both directions: a naming
   * flip renames {@code multi_word_name} and a lost inclusion setting adds {@code absent_when_null:
   * null}.
   */
  @Test
  void bootMapper_serializesSnakeCaseAndOmitsNulls() throws Exception {
    String json =
        objectMapper.writeValueAsString(
            new Probe("value", null, Instant.parse("2026-08-27T10:00:00Z")));

    assertEquals(
        "{\"multi_word_name\":\"value\",\"when\":\"2026-08-27T10:00:00Z\"}",
        json,
        "the shipped jackson block must produce snake_case keys with nulls omitted");
  }
}
