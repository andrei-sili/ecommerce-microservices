package com.ecommerce.cart.security;

import com.ecommerce.cart.security.JwtService.AuthenticatedUser;
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
 * context with {@code ROLE_*} authorities and the JWT {@code sub} as the principal name. A missing
 * or invalid token leaves the context unauthenticated; every cart endpoint then rejects it with a
 * 401 through the authorization rules.
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
        // Deliberately narrow (not catch-all): any other RuntimeException is a real defect and must
        // surface, not be silently swallowed into an incidental 401. The parse-level guards
        // (null-kid, roles-null) throw JwtException subtypes, so they stay inside this catch.
        SecurityContextHolder.clearContext();
      }
    }
    filterChain.doFilter(request, response);
  }
}
