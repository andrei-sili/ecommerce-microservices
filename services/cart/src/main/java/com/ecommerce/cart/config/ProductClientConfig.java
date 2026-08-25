package com.ecommerce.cart.config;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} used to call the Product Service with bounded timeouts.
 *
 * <p>The builder is Boot's auto-configured one, never the static factory method on {@link
 * RestClient}: only the injected builder carries the message converters built from the
 * application's own {@code ObjectMapper}, and therefore the {@code SNAKE_CASE} naming strategy the
 * Product contract is written in. The static builder silently falls back to Jackson's camelCase
 * defaults, which parse a multi-token field such as {@code stock_quantity} to {@code null} instead
 * of failing.
 */
@Configuration
public class ProductClientConfig {

  @Bean
  public RestClient productRestClient(
      RestClient.Builder builder, ProductClientProperties properties) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
            .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
    // clone() so this bean never mutates the shared builder — matching order's and payment's client
    // configs. Prototype scope makes it harmless today; being the fleet's only non-cloning site is
    // the cost, and it stops being harmless the moment that scope changes.
    return builder
        .clone()
        .baseUrl(properties.baseUrl())
        .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
        .build();
  }
}
