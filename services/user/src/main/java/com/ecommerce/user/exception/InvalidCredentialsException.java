package com.ecommerce.user.exception;

import org.springframework.http.HttpStatus;

/** Generic auth failure — must never reveal whether the email exists or the password was wrong. */
public class InvalidCredentialsException extends ApiException {

  public InvalidCredentialsException() {
    super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
  }
}
