package com.ecommerce.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "product.service")
public record ProductClientProperties(
    String baseUrl, long connectTimeoutMs, long readTimeoutMs) {

  public ProductClientProperties {
    if (connectTimeoutMs <= 0) {
      connectTimeoutMs = 2000;
    }
    if (readTimeoutMs <= 0) {
      readTimeoutMs = 3000;
    }
  }
}
