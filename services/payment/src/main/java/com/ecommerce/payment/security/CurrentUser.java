package com.ecommerce.payment.security;

import java.util.List;

/**
 * Resolved caller identity for the current request. {@code bearerToken} is the raw JWT (the
 * {@code Bearer } prefix is stripped by {@link JwtAuthenticationFilter} before storing it here).
 * Callers that forward this token to other services MUST add the prefix themselves.
 */
public record CurrentUser(Long userId, List<String> roles, String bearerToken) {

  private static final String ADMIN_ROLE = "ADMIN";

  public boolean isAdmin() {
    return roles.contains(ADMIN_ROLE);
  }
}
