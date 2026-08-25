package com.ecommerce.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ecommerce.cart.security.RestAccessDeniedHandler;
import com.ecommerce.cart.security.RestAuthenticationEntryPoint;
import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Binds the two invariants that a green HTTP suite can only prove indirectly, at the bean level.
 *
 * <p>Cart has no {@code src/test/resources}, so the context under test reads the SHIPPED {@code
 * application.yml}: unlike user, order and payment, whose test yml fully shadows the real file,
 * these assertions are evidence about production (B9, §6.9). A silently unbound {@code
 * spring.jackson.*} property — the exact failure mode a serialization migration produces — fails at
 * build time here instead of at the edge, where only a body-shaped assertion would notice.
 */
class ShippedConfigBindingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;

  /**
   * A1: the 401/403 renderers are constructor-injected with the Boot mapper, so a mapper-injection
   * site that stops resolving takes the whole envelope down. The grep pair in the invariant proves
   * only that an import moved; this proves the beans exist and share the configured mapper.
   */
  @Test
  void securityEnvelopeRenderers_arePresent_andShareTheBootMapper() {
    RestAuthenticationEntryPoint entryPoint =
        applicationContext.getBean(RestAuthenticationEntryPoint.class);
    RestAccessDeniedHandler accessDeniedHandler =
        applicationContext.getBean(RestAccessDeniedHandler.class);

    assertNotNull(entryPoint, "RestAuthenticationEntryPoint must be a context bean");
    assertNotNull(accessDeniedHandler, "RestAccessDeniedHandler must be a context bean");
    assertSame(
        objectMapper,
        applicationContext.getBean(ObjectMapper.class),
        "the renderers must resolve the single auto-configured mapper, not a private one");
  }

  /**
   * B9: {@code property-naming-strategy: SNAKE_CASE} and {@code default-property-inclusion:
   * non_null} are the two shipped properties every REST body in this service depends on — the first
   * for the whole snake_case contract, the second for {@code currency} being OMITTED rather than
   * null on an empty cart.
   */
  @Test
  void bootMapper_bindsSnakeCaseAndNonNullInclusion_fromTheShippedYml() {
    assertEquals(
        PropertyNamingStrategies.SNAKE_CASE,
        objectMapper.getSerializationConfig().getPropertyNamingStrategy(),
        "spring.jackson.property-naming-strategy did not bind onto the Boot mapper");
    assertEquals(
        JsonInclude.Include.NON_NULL,
        objectMapper.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion(),
        "spring.jackson.default-property-inclusion did not bind onto the Boot mapper");
  }
}
