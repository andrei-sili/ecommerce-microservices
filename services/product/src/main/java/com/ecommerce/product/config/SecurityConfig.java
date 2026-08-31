package com.ecommerce.product.config;

import com.ecommerce.product.security.InternalApiKeyFilter;
import com.ecommerce.product.security.JwtAuthenticationFilter;
import com.ecommerce.product.security.JwtService;
import com.ecommerce.product.security.RestAccessDeniedHandler;
import com.ecommerce.product.security.RestAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

  /**
   * The internal reservation endpoints, gated by the shared internal API key. Both the base path
   * (POST) and sub-paths (DELETE /{orderId}) are listed explicitly so the broad write rules below
   * never apply to them regardless of how {@code /**} treats the trailing segment.
   */
  private static final String[] RESERVATIONS_PATHS = {
    "/api/v1/inventory/reservations", "/api/v1/inventory/reservations/**"
  };

  private final JwtService jwtService;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;
  private final ObjectMapper objectMapper;
  private final String internalApiKey;

  public SecurityConfig(
      JwtService jwtService,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler,
      ObjectMapper objectMapper,
      @Value("${security.internal-api-key}") String internalApiKey) {
    this.jwtService = jwtService;
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.accessDeniedHandler = accessDeniedHandler;
    this.objectMapper = objectMapper;
    if (!StringUtils.hasText(internalApiKey)) {
      throw new IllegalStateException("INTERNAL_API_KEY must be configured");
    }
    this.internalApiKey = internalApiKey;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // Prometheus scrape endpoint: unauthenticated on purpose but ClusterIP-internal
                    // only (never routed through Kong, never under /api/v1). Wave 5c metrics.
                    .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                    .permitAll()
                    // Internal reservation endpoints are gated by the X-Internal-Api-Key filter,
                    // not by JWT/ADMIN. Permit them here so the broad write rules below don't force
                    // ADMIN; the InternalApiKeyFilter (added before the JWT filter) is the gate.
                    .requestMatchers(RESERVATIONS_PATHS)
                    .permitAll()
                    // Reads are public.
                    .requestMatchers(HttpMethod.GET, "/api/v1/**")
                    .permitAll()
                    // Every write to the catalog/inventory requires ADMIN.
                    .requestMatchers(HttpMethod.POST, "/api/v1/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
        // Runs before the JWT filter; only acts on the reservation paths (see shouldNotFilter).
        .addFilterBefore(
            new InternalApiKeyFilter(internalApiKey, objectMapper), JwtAuthenticationFilter.class);

    return http.build();
  }
}
