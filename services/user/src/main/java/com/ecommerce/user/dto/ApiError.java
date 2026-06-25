package com.ecommerce.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String error, String message, Instant timestamp, String path, List<FieldViolation> fields) {

  public record FieldViolation(String field, String message) {}

  public static ApiError of(String error, String message, String path) {
    return new ApiError(error, message, Instant.now(), path, null);
  }

  public static ApiError validation(String message, String path, List<FieldViolation> fields) {
    return new ApiError("VALIDATION_ERROR", message, Instant.now(), path, fields);
  }
}
