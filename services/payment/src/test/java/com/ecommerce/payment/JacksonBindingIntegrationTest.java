package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * Contract B9, bean level: the autowired Boot {@link ObjectMapper} must carry {@code SNAKE_CASE}
 * naming and {@code NON_NULL} default inclusion, so a silently-unbound {@code spring.jackson.*}
 * property fails at build time instead of at the edge.
 *
 * <p><b>Why a bean-level assertion when the suite is full of body assertions.</b> Every
 * output-level assertion in this suite reaches the mapper through an HTTP round trip, so a naming
 * flip breaks request <i>deserialization</i> first and the row fails at {@code Status
 * expected:<201> but was:<400>} — a real failure attributed to the wrong cause. Measured under
 * mutation M2: 26 failures, of which not one named a casing or inclusion assertion. These three do.
 *
 * <p><b>Where the values come from is the point.</b> Until this slice, {@code
 * src/test/resources/application.yml} shadowed the shipped file (first {@code
 * classpath:/application.yml} on the classpath wins, and {@code target/test-classes} precedes
 * {@code target/classes}), so the suite validated a configuration it handed itself: emptying the
 * SHIPPED file left all 106 tests green. {@link #bootMapperConfigIsReadFromTheShippedFile()} is the
 * structural guard against that returning — it fails the moment any {@code application.yml} is
 * reintroduced under {@code src/test/resources}, whatever the file happens to contain.
 */
class JacksonBindingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ObjectMapper objectMapper;

  // Same override shape as the rest of the suite so this class reuses the cached context.
  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  @Test
  void bootMapper_serializesWithSnakeCaseNaming() {
    assertThat(objectMapper.serializationConfig().getPropertyNamingStrategy())
        .as("spring.jackson.property-naming-strategy, bound from the SHIPPED application.yml")
        .isSameAs(PropertyNamingStrategies.SNAKE_CASE);
  }

  /**
   * The inbound half of the same property. It is asserted separately because it is what actually
   * breaks first under a naming flip: request bodies stop binding and every money row degrades to a
   * 400 before any response-shape assertion runs.
   */
  @Test
  void bootMapper_deserializesWithSnakeCaseNaming() {
    assertThat(objectMapper.deserializationConfig().getPropertyNamingStrategy())
        .as("spring.jackson.property-naming-strategy on the inbound side")
        .isSameAs(PropertyNamingStrategies.SNAKE_CASE);
  }

  /**
   * {@code default-property-inclusion: non_null} is what suppresses {@code failure_reason} on a
   * successful 201. Losing it makes every money body gain null-valued keys.
   */
  @Test
  void bootMapper_defaultPropertyInclusionIsNonNull() {
    assertThat(objectMapper.serializationConfig().getDefaultPropertyInclusion().getValueInclusion())
        .as("spring.jackson.default-property-inclusion, bound from the SHIPPED application.yml")
        .isEqualTo(JsonInclude.Include.NON_NULL);
  }

  /**
   * Structural control for the three assertions above: they are only evidence about production if
   * the document they read is the shipped one. A test-tree {@code application.yml} would shadow it
   * and make all three self-referential again.
   */
  @Test
  void bootMapperConfigIsReadFromTheShippedFile() {
    URL applicationYml = JacksonBindingIntegrationTest.class.getResource("/application.yml");

    assertThat(applicationYml)
        .as("classpath:/application.yml must resolve - it is the base document of the test profile")
        .isNotNull();
    assertThat(applicationYml.getPath())
        .as("classpath:/application.yml must resolve to the SHIPPED file, never a test-tree copy")
        .doesNotContain("/test-classes/")
        .endsWith("/target/classes/application.yml");
  }
}
