package com.ecommerce.cart.security;

import com.ecommerce.cart.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the caller's user id strictly from the JWT {@code sub} — never from a path or body. */
public final class CurrentUser {

  private CurrentUser() {}

  public static long requireUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }
    try {
      return Long.parseLong(String.valueOf(authentication.getName()));
    } catch (NumberFormatException ex) {
      // A token whose `sub` is not a numeric user id is not one we can own a cart for.
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid token subject");
    }
  }
}
