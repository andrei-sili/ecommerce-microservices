package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Asserts the three {@code spring.jackson.*} properties this service's wire contract rests on are
 * actually BOUND on the injected mapper, rather than inferring it from a body that happens to look
 * right.
 *
 * <p>The distinction matters at exactly one moment: a rename or a re-scope on the framework side
 * leaves the key in the yml silently unbound. Every REST assertion in this suite would then fail at
 * once, in a cascade of unrelated-looking body mismatches, and the actual cause — one property that
 * stopped binding — would have to be reconstructed from them. This class fails first and names it.
 *
 * <p>Scope limit, stated rather than papered over: {@code user}'s {@code
 * src/test/resources/application.yml} fully shadows the shipped file, and carries its own copy of
 * the same jackson block. So a green here proves the property still binds — the migration risk this
 * pin exists for — but says nothing about the SHIPPED yml being the file that supplied it. Closing
 * that half is the shadow slice's job (contract §6.9, §10); it is deliberately not worked around
 * here, because asserting against the test yml would make the pin blinder, not better.
 */
@AutoConfigureMockMvc
class JacksonBindingIntegrationTest extends AbstractIntegrationTest {

  private record Probe(String multiWordName, String nullField, Instant when) {}

  @Autowired private ObjectMapper objectMapper;

  @Test
  void bootMapper_usesSnakeCaseNaming() {
    assertEquals(
        PropertyNamingStrategies.SNAKE_CASE,
        objectMapper.getSerializationConfig().getPropertyNamingStrategy(),
        "spring.jackson.property-naming-strategy must still bind — REST bodies are snake_case");
  }

  @Test
  void bootMapper_suppressesNulls() {
    assertEquals(
        JsonInclude.Include.NON_NULL,
        objectMapper.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion(),
        "spring.jackson.default-property-inclusion must still bind — it is the fleet-wide default"
            + " that suppresses nulls in response bodies. It is NOT what holds the error envelope"
            + " at four keys: ApiError carries its own @JsonInclude(NON_NULL) at type level (A3),"
            + " so the envelope survives this property independently.");
  }

  @Test
  void bootMapper_writesDatesAsIsoStrings_notEpochNumbers() throws Exception {
    assertFalse(
        objectMapper
            .getSerializationConfig()
            .isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
        "spring.jackson.serialization.write-dates-as-timestamps must still bind as false");

    String json =
        objectMapper.writeValueAsString(
            new Probe("value", null, Instant.parse("2026-08-04T08:26:57Z")));

    assertEquals("{\"multi_word_name\":\"value\",\"when\":\"2026-08-04T08:26:57Z\"}", json);
  }
}
