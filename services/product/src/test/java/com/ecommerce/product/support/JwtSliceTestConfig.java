package com.ecommerce.product.support;

import com.ecommerce.product.config.JwtProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the two collaborators {@link com.ecommerce.product.security.JwtService} now needs inside
 * a {@code @WebMvcTest} slice (which excludes both metrics auto-config and the app's
 * {@code @ConfigurationPropertiesScan}): a {@link MeterRegistry} and the bound {@link
 * JwtProperties}. Slices run with {@code accepted-algs=HS256} (HS256-only tokens), so no RSA public
 * key is required — the full RS256 matrix is covered by the full-context dual-accept suites.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtSliceTestConfig {

  @Bean
  MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }
}
