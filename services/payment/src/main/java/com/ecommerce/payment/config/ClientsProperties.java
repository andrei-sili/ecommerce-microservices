package com.ecommerce.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clients")
public class ClientsProperties {

  private Order order = new Order();
  private long connectTimeoutMs = 2000;
  private long readTimeoutMs = 5000;

  public Order getOrder() {
    return order;
  }

  public long getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(long connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public long getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(long readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }

  public static class Order {
    private String baseUrl = "http://order-service:8084";

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }
  }
}
