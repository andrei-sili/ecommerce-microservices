package com.ecommerce.payment.exception;

import java.time.Instant;

public class ErrorResponse {

  private String error;
  private String message;
  private Instant timestamp;
  private String path;

  private ErrorResponse() {}

  public static ErrorResponse of(String error, String message, String path) {
    ErrorResponse r = new ErrorResponse();
    r.error = error;
    r.message = message;
    r.timestamp = Instant.now();
    r.path = path;
    return r;
  }

  public String getError() {
    return error;
  }

  public String getMessage() {
    return message;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public String getPath() {
    return path;
  }
}
