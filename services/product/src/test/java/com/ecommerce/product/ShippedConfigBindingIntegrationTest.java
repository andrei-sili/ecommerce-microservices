package com.ecommerce.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ecommerce.product.security.InternalApiKeyFilter;
import com.ecommerce.product.security.RestAccessDeniedHandler;
import com.ecommerce.product.security.RestAuthenticationEntryPoint;
import com.ecommerce.product.support.AbstractIntegrationTest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Binds, at the bean level, the two serialization invariants that a green HTTP suite can only prove
 * indirectly.
 *
 * <p>Product has no {@code src/test/resources}, so the context under test reads the SHIPPED {@code
 * application.yml}: unlike user, order and payment, whose test yml fully shadows the real file,
 * these assertions are evidence about production (B9, §6.9). A silently unbound {@code
 * spring.jackson.*} property — the exact failure mode a serialization migration produces — fails at
 * build time here instead of at the edge, where only a body-shaped assertion would notice.
 */
class ShippedConfigBindingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ApplicationContext applicationContext;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private List<SecurityFilterChain> securityFilterChains;

  /**
   * A1: the 401/403 renderers are constructor-injected with the Boot mapper, so a mapper-injection
   * site that stops resolving takes the whole envelope down. The grep pair in the invariant proves
   * only that an import moved; this proves the beans exist and that {@code ObjectMapper} resolves
   * unambiguously to the single auto-configured instance their constructors consume.
   *
   * <p>Product's THIRD mapper-injection site is covered separately — see {@link
   * #internalApiKeyFilter_isInstalledInTheChain_withItsMapperResolved()}, which explains why it
   * cannot be asserted the way A1 prescribes.
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
   * A1's intent for product's third mapper-injection site, discharged by a mechanism A1 does not
   * describe.
   *
   * <p>A1's evidence column asks for {@code ctx.getBean(...)} on the two renderers "(and {@code
   * InternalApiKeyFilter} on product)". That last clause is NOT dischargeable as written, verified
   * at 1078cad: the filter carries no stereotype and is never registered as a bean. {@code
   * SecurityConfig} constructs it inline and installs it via {@code addFilterBefore(new
   * InternalApiKeyFilter(internalApiKey, objectMapper), JwtAuthenticationFilter.class)}, so {@code
   * getBean(InternalApiKeyFilter.class)} throws {@code NoSuchBeanDefinitionException}. It is
   * therefore asserted through the filter chain BECAUSE it is not a bean — do not "simplify" this
   * back to a {@code getBean} lookup; that is the form that cannot work.
   *
   * <p>Mapper identity, not envelope shape, is what this adds. The envelope itself is already
   * pinned exhaustively by {@code ErrorEnvelopePinIT#internalApiKeyFilter_pins401EnvelopeToExactly
   * FourKeys_andNeverEchoesTheKey}. What behaviour cannot discriminate is a
   * CONFIGURATION-EQUIVALENT mapper (e.g. {@code objectMapper.copy()}): all four envelope keys
   * ({@code error}, {@code message}, {@code timestamp}, {@code path}) are single-token, so naming
   * strategy cannot separate them. A bare {@code new ObjectMapper()} IS caught behaviourally —
   * measured, not assumed: it reddens {@code ErrorEnvelopePinIT} and {@code
   * ReservationIT#missingOrWrongInternalKeyIsUnauthorized} with "Java 8 date/time type
   * java.time.Instant not supported by default", because the discriminating axis is {@code
   * ErrorResponse.timestamp} being an {@code Instant} with no {@code JavaTimeModule}, NOT the
   * naming strategy.
   *
   * <p>So state the value honestly: the harmful substitution is already caught hard, twice over.
   * This assertion's unique detection power covers the benign-today case — a configuration-
   * equivalent copy, invisible to all 134 behavioural tests — and its worth is future-drift
   * protection plus A1 coverage of the third mapper-injection site, not catching a live bug. Its
   * negative control is {@code objectMapper.copy()} at the construction site, which yields exactly
   * one red in the suite: this one.
   *
   * <p>The field read is acceptable BECAUSE it fails loud, never silently green: {@code
   * ReflectionTestUtils.getField} throws {@code IllegalArgumentException("Could not find field '%s'
   * on %s or target class [%s]")} when the field is absent (verified in spring-test-6.2.19.jar), so
   * renaming {@code objectMapper} breaks this test by name rather than quietly passing.
   */
  @Test
  void internalApiKeyFilter_isInstalledInTheChain_withItsMapperResolved() {
    List<InternalApiKeyFilter> installed =
        securityFilterChains.stream()
            .flatMap(chain -> chain.getFilters().stream())
            .filter(InternalApiKeyFilter.class::isInstance)
            .map(InternalApiKeyFilter.class::cast)
            .toList();

    assertEquals(
        1,
        installed.size(),
        "exactly one InternalApiKeyFilter must be installed in the security filter chain");
    assertSame(
        objectMapper,
        ReflectionTestUtils.getField(installed.get(0), "objectMapper"),
        "InternalApiKeyFilter must render its 401 envelope with the container's ObjectMapper,"
            + " not a privately constructed one");
  }

  /**
   * B9: {@code property-naming-strategy: SNAKE_CASE} and {@code default-property-inclusion:
   * non_null} are the two shipped properties every REST body in this service depends on — the first
   * for the whole snake_case contract ({@code stock_quantity}, {@code created_at}, {@code
   * total_elements}), the second for a description-less product serialising with the key OMITTED
   * rather than as {@code "description": null} ({@code products.description} is nullable TEXT,
   * {@code V2__create_products_table.sql:5}).
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
