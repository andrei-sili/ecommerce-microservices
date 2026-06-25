package com.ecommerce.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
    String secret, long accessTokenTtlSeconds, long refreshTokenTtlSeconds, String issuer) {}
