package com.ecommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Downstream service endpoints and timeouts (bound from the {@code clients.*} config tree). */
@ConfigurationProperties(prefix = "clients")
public class ClientsProperties {

  private Cart cart = new Cart();
  private Product product = new Product();
  private int connectTimeoutMs = 2000;
  private int readTimeoutMs = 5000;

  public Cart getCart() {
    return cart;
  }

  public void setCart(Cart cart) {
    this.cart = cart;
  }

  public Product getProduct() {
    return product;
  }

  public void setProduct(Product product) {
    this.product = product;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }

  public static class Cart {
    private String baseUrl;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }
  }

  public static class Product {
    private String baseUrl;
    private String internalApiKey;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getInternalApiKey() {
      return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
      this.internalApiKey = internalApiKey;
    }
  }
}
