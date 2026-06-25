package com.ecommerce.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ClientsProperties.class)
public class ClientsConfig {

  /** Shared request factory: bounded connect/read timeouts so a slow upstream cannot hang Order. */
  @Bean
  public ClientHttpRequestFactory clientHttpRequestFactory(ClientsProperties properties) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .withReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
    return ClientHttpRequestFactories.get(settings);
  }

  @Bean
  public RestClient cartRestClient(
      ClientsProperties properties, ClientHttpRequestFactory requestFactory) {
    return RestClient.builder()
        .baseUrl(properties.getCart().getBaseUrl())
        .requestFactory(requestFactory)
        .build();
  }

  @Bean
  public RestClient productRestClient(
      ClientsProperties properties, ClientHttpRequestFactory requestFactory) {
    return RestClient.builder()
        .baseUrl(properties.getProduct().getBaseUrl())
        .requestFactory(requestFactory)
        .build();
  }
}
