package com.ecommerce.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Shared client infrastructure. {@link com.ecommerce.payment.client.OrderClient} is a
 * {@code @Component} that takes the auto-configured {@code RestClient.Builder} bean directly —
 * this config only adds the shared timeout customiser.
 */
@Configuration
@EnableConfigurationProperties(ClientsProperties.class)
public class ClientsConfig {

  /**
   * Applies bounded connect/read timeouts via a {@link RestClientCustomizer}. Ordered first so
   * later customisers can still override the factory. A slow Order Service cannot hang Payment.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public RestClientCustomizer timeoutRestClientCustomizer(ClientsProperties properties) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .withReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
    return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
  }
}
