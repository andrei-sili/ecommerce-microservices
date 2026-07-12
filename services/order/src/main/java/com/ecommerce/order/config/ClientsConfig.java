package com.ecommerce.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Shared client infrastructure: the bounded-timeout customizer and the {@code clients.*}
 * properties. The two REST clients live in {@link CartClientConfig} / {@link ProductClientConfig}
 * (one bean each) so a wire test can load a single client per context.
 */
@Configuration
@EnableConfigurationProperties(ClientsProperties.class)
@Import({CartClientConfig.class, ProductClientConfig.class})
public class ClientsConfig {

  /**
   * Applies bounded connect/read timeouts via a {@link RestClientCustomizer} (not an imperative
   * {@code .requestFactory(...)} call on a derived client) so it composes with Boot's other builder
   * customizers instead of replacing the factory they set. Ordered first so any later customizer
   * that swaps the request factory still wins. A slow upstream cannot hang Order.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public RestClientCustomizer timeoutRestClientCustomizer(ClientsProperties properties) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .withReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
    ClientHttpRequestFactory requestFactory =
        ClientHttpRequestFactoryBuilder.detect().build(settings);
    return builder -> builder.requestFactory(requestFactory);
  }
}
