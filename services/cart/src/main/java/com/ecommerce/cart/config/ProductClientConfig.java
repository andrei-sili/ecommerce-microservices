package com.ecommerce.cart.config;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Builds the {@link RestClient} used to call the Product Service with bounded timeouts. */
@Configuration
public class ProductClientConfig {

  @Bean
  public RestClient productRestClient(ProductClientProperties properties) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
            .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(ClientHttpRequestFactories.get(settings))
        .build();
  }
}
