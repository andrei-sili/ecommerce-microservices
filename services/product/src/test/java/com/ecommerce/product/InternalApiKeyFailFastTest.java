package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.product.config.SecurityConfig;
import com.ecommerce.product.security.JwtService;
import com.ecommerce.product.security.RestAccessDeniedHandler;
import com.ecommerce.product.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A13's fail-closed half: product must refuse to START without an internal API key, rather than
 * come up with a reservation gate that compares every caller's header against an empty string.
 *
 * <p>Every other suite in this module supplies a valid {@code security.internal-api-key}, so the
 * guard at {@code SecurityConfig}'s constructor had no witness at all — the throw was reachable
 * only by deploying without the variable. The failure it prevents is silent in the worst way:
 * {@code MessageDigest.isEqual} against a zero-length expected key rejects every real caller, so
 * the service would boot healthy and then 401 every reservation from order.
 *
 * <p>Driven through a context runner rather than by calling the constructor directly, because the
 * contract's claim is about STARTUP: what must be proven is that the throw propagates as a context
 * failure and the application does not come up, not merely that a constructor can throw. The
 * runner's {@code getStartupFailure()} is the same path {@code SpringApplication.run} takes to a
 * non-zero exit.
 */
class InternalApiKeyFailFastTest {

  private static final String MESSAGE = "INTERNAL_API_KEY must be configured";

  /**
   * {@code SecurityConfig}'s own collaborators, plus the two auto-configurations that supply {@code
   * HttpSecurity} — the real chain builds it, so a runner without it fails for a reason that has
   * nothing to do with the key.
   *
   * <p>That is not a hypothetical. The first version of this class omitted them and the positive
   * control below caught it: every row "failed to start", but with {@code UnsatisfiedDependency ...
   * HttpSecurity}, so the two negative rows would have stayed green with the guard deleted. Keeping
   * the third row is what makes the other two mean anything.
   */
  private WebApplicationContextRunner runnerWithKey(String key) {
    return new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
        .withBean(ObjectMapper.class, JsonMapper::new)
        .withBean(JwtService.class, () -> Mockito.mock(JwtService.class))
        .withBean(
            RestAuthenticationEntryPoint.class,
            () -> Mockito.mock(RestAuthenticationEntryPoint.class))
        .withBean(RestAccessDeniedHandler.class, () -> Mockito.mock(RestAccessDeniedHandler.class))
        .withPropertyValues("security.internal-api-key=" + key)
        .withUserConfiguration(SecurityConfig.class);
  }

  /** {@code INTERNAL_API_KEY=} in the environment binds as the empty string, not as absent. */
  @Test
  void emptyInternalApiKey_failsStartup_namingTheEnvironmentVariable() {
    runnerWithKey("")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .as("an empty key must abort startup, never boot a gate that rejects everyone")
                  .hasRootCauseMessage(MESSAGE);
            });
  }

  /** Whitespace is the same failure: {@code StringUtils.hasText}, not {@code isEmpty}. */
  @Test
  void blankInternalApiKey_failsStartup_namingTheEnvironmentVariable() {
    runnerWithKey("   ")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasRootCauseMessage(MESSAGE);
            });
  }

  /**
   * The positive control, and the reason the two rows above are evidence rather than a tautology.
   * Without it, a runner that failed for some unrelated reason — a missing collaborator, a bad
   * property name — would satisfy {@code hasFailed()} on every row and the guard could be deleted
   * with the class still green.
   */
  @Test
  void configuredInternalApiKey_startsCleanly() {
    runnerWithKey("a-real-key")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SecurityConfig.class);
            });
  }
}
