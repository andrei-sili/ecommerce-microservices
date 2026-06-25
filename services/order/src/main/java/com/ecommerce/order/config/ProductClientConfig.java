package com.ecommerce.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the Product {@link RestClient} from Boot's auto-configured {@code RestClient.Builder} so
 * it inherits Boot's HttpMessageConverters — including the snake_case ObjectMapper from {@code
 * spring.jackson.property-naming-strategy}. The static {@code RestClient.builder()} would use a
 * default (camelCase) mapper and break the snake_case wire contract in both directions. Timeouts
 * are applied to the shared builder by {@code ClientsConfig}'s RestClientCustomizer.
 */
@Configuration
public class ProductClientConfig {

  @Bean
  public RestClient productRestClient(RestClient.Builder builder, ClientsProperties properties) {
    return builder.clone().baseUrl(properties.getProduct().getBaseUrl()).build();
  }
}
