package com.ecommerce.payment.security;

import java.util.List;

/** Resolved caller identity for the current request: numeric user id, roles, and the raw token. */
public record CurrentUser(Long userId, List<String> roles, String bearerToken) {

  private static final String ADMIN_ROLE = "ADMIN";

  public boolean isAdmin() {
    return roles.contains(ADMIN_ROLE);
  }
}
