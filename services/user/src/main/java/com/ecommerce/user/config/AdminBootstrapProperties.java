package com.ecommerce.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin.bootstrap")
public record AdminBootstrapProperties(String email, String password) {

  public boolean isConfigured() {
    return email != null && !email.isBlank() && password != null && !password.isBlank();
  }
}
