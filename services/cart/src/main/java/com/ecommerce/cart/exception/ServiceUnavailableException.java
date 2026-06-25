package com.ecommerce.cart.exception;

import org.springframework.http.HttpStatus;

/** Raised when a downstream dependency (e.g. Product Service) is unreachable or times out. */
public class ServiceUnavailableException extends ApiException {

  public ServiceUnavailableException(String code, String message) {
    super(HttpStatus.SERVICE_UNAVAILABLE, code, message);
  }
}
