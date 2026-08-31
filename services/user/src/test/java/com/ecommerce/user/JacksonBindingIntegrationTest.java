package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Asserts the serialization settings this service's wire contract rests on are actually in force on
 * the injected mapper, rather than inferring it from a body that happens to look right.
 *
 * <p>Two of the three come from {@code spring.jackson.*} keys in the shipped yml and are asserted
 * as BOUND; the third is now a Jackson 3 default, asserted as still-off — see that method's own
 * note for why its property had to be deleted.
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
        objectMapper.serializationConfig().getPropertyNamingStrategy(),
        "spring.jackson.property-naming-strategy must still bind — REST bodies are snake_case");
  }

  @Test
  void bootMapper_suppressesNulls() {
    assertEquals(
        JsonInclude.Include.NON_NULL,
        objectMapper.serializationConfig().getDefaultPropertyInclusion().getValueInclusion(),
        "spring.jackson.default-property-inclusion must still bind — it is the fleet-wide default"
            + " that suppresses nulls in response bodies. It is NOT what holds the error envelope"
            + " at four keys: ApiError carries its own @JsonInclude(NON_NULL) at type level (A3),"
            + " so the envelope survives this property independently.");
  }

  /**
   * The one row here that pins a Jackson 3 <em>default</em> rather than a bound property.
   *
   * <p>The {@code spring.jackson.serialization.*} entry naming the flag below used to hold this,
   * and was deleted from the shipped yml with no replacement (contract B8). It had to go: Jackson 3
   * moved the flag off {@code SerializationFeature} onto {@code DateTimeFeature}, and Boot binds
   * that property map by {@code SerializationFeature} constant name, so the key stops resolving and
   * every context dies on a binding error. Measured rather than read from a changelog — {@code
   * tools.jackson.databind.SerializationFeature} genuinely has no such constant, and the compiler
   * said so before the yml was touched.
   *
   * <p>The deleted key is described here rather than spelled, deliberately: B8's gate is a grep for
   * that exact kebab-case token across {@code services/}, and prose explaining a deletion must not
   * read to a scanner as a surviving declaration. Do not "restore" the literal for readability —
   * the constant named in the assertion below identifies the flag unambiguously.
   *
   * <p>So what used to be "the property still binds" is now "the default is still off". The
   * behavioural half below is unchanged and is the stronger of the two: it fails on an epoch number
   * whatever the mechanism, where the config-level assertion above fails only on the mechanism we
   * currently expect. Both are kept because they fail at different distances from the cause.
   */
  @Test
  void bootMapper_writesDatesAsIsoStrings_notEpochNumbers() throws Exception {
    assertFalse(
        objectMapper.serializationConfig().isEnabled(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS),
        "Jackson 3 must keep WRITE_DATES_AS_TIMESTAMPS off by default — nothing in the shipped yml"
            + " sets it any more, so this default IS the contract for every date on the wire");

    String json =
        objectMapper.writeValueAsString(
            new Probe("value", null, Instant.parse("2026-08-04T08:26:57Z")));

    assertEquals("{\"multi_word_name\":\"value\",\"when\":\"2026-08-04T08:26:57Z\"}", json);
  }
}
