package com.ecommerce.product.security;

import com.ecommerce.product.security.JwtService.AuthenticatedUser;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code Authorization: Bearer <token>} header, validates it, and populates the security
 * context with {@code ROLE_*} authorities. A missing or invalid token simply leaves the context
 * unauthenticated — public reads still proceed; protected writes are rejected downstream by the
 * authorization rules (401 unauthenticated, 403 authenticated-without-ADMIN).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;

  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      String token = header.substring(BEARER_PREFIX.length()).trim();
      try {
        AuthenticatedUser user = jwtService.parse(token);
        List<SimpleGrantedAuthority> authorities =
            user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        var authentication =
            new UsernamePasswordAuthenticationToken(user.subject(), null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (JwtException | IllegalArgumentException ex) {
        // Invalid/expired token: leave the context unauthenticated; the entry point returns 401.
        // Narrow on purpose (parity with user): only JWT-shaped failures are swallowed here. A
        // no-kid or null-role token would otherwise raise a raw NPE (a non-JWT RuntimeException)
        // that escapes this catch and surfaces as a 500; the JwtService guards convert those into
        // JwtExceptions caught here as 401, which is exactly what makes those guards load-bearing.
        SecurityContextHolder.clearContext();
      }
    }
    filterChain.doFilter(request, response);
  }
}
