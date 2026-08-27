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
 * <p><strong>The scope limit this class used to carry is closed.</strong> Until the S-shadow slice,
 * {@code user}'s {@code src/test/resources/application.yml} fully shadowed the shipped file and
 * carried its own copy of the same jackson block, so a green here proved only that the property
 * still binds — never that the SHIPPED yml was the file that supplied it. The test config is now a
 * profile overlay ({@code application-test.yml}) that deliberately declares no {@code
 * spring.jackson.*} key, so these three assertions read {@code src/main/resources/application.yml}.
 *
 * <p>Verified rather than assumed, at {@code f766efd}: deleting {@code default-property-inclusion}
 * and flipping {@code property-naming-strategy} to {@code LOWER_CAMEL_CASE} in the SHIPPED file
 * turns all three rows below red and names the property in each message. The same mutation on the
 * pre-refactor tree left the whole suite green. <strong>Never re-declare {@code spring.jackson.*}
 * in the test overlay</strong> — that single line is what made this pin vacuous for the whole of
 * its previous life.
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
