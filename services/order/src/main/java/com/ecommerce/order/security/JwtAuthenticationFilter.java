package com.ecommerce.order.security;

import com.ecommerce.order.security.JwtService.AuthenticatedUser;
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
 * context with {@code ROLE_*} authorities. The raw token is kept as the authentication credentials
 * so the placement saga can forward it on user-scoped calls to Cart. A missing or invalid token
 * leaves the context unauthenticated; every Order endpoint then rejects it with 401.
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
            new UsernamePasswordAuthenticationToken(user.subject(), token, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (Exception ex) {
        // Invalid/expired token: leave the context unauthenticated. Do not leak details.
        SecurityContextHolder.clearContext();
      }
    }
    filterChain.doFilter(request, response);
  }
}
