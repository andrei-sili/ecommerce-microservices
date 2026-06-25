package com.ecommerce.order.exception;

import org.springframework.http.HttpStatus;

/**
 * A downstream dependency (Cart or Product) was unreachable, timed out, or replied unexpectedly.
 * Surfaced as 502 with a generic message — the upstream's internals never reach the client.
 */
public class UpstreamServiceException extends ApiException {

  public UpstreamServiceException(String message) {
    super(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", message);
  }
}
