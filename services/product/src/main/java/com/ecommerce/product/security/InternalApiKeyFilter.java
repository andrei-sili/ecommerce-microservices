package com.ecommerce.product.security;

import com.ecommerce.product.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gate for the internal reservation endpoints ({@code /api/v1/inventory/reservations**}). Requires
 * a shared {@code X-Internal-Api-Key} that equals the configured {@code INTERNAL_API_KEY} (system
 * call, not a user JWT and not the ADMIN role). The comparison is constant-time to avoid leaking
 * the key through timing. A missing or wrong key is rejected with the standard 401 error body; the
 * key is never echoed.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Internal-Api-Key";
  private static final String PROTECTED_PREFIX = "/api/v1/inventory/reservations";

  private final byte[] expectedKey;
  private final ObjectMapper objectMapper;

  public InternalApiKeyFilter(String expectedKey, ObjectMapper objectMapper) {
    this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String provided = request.getHeader(HEADER);
    if (provided == null || !matches(provided)) {
      writeUnauthorized(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean matches(String provided) {
    return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKey);
  }

  private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getWriter(),
        ErrorResponse.of("UNAUTHORIZED", "Invalid internal API key", request.getRequestURI()));
  }
}
