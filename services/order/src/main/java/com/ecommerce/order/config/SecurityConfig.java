package com.ecommerce.order.config;

import com.ecommerce.order.security.JwtAuthenticationFilter;
import com.ecommerce.order.security.JwtService;
import com.ecommerce.order.security.RestAccessDeniedHandler;
import com.ecommerce.order.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

  private final JwtService jwtService;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  public SecurityConfig(
      JwtService jwtService,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler) {
    this.jwtService = jwtService;
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.accessDeniedHandler = accessDeniedHandler;
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
                    // Every order endpoint requires a valid JWT; ownership is enforced per request
                    // in the service layer (a user acts only on their own orders).
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
