package com.ecommerce.user.exception;

import com.ecommerce.user.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as the standard {@code {error, message, timestamp, path}} envelope.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the ~20 framework/dispatcher exceptions
 * (unmapped path, wrong method, bad media type, unreadable body, validation) all funnel through
 * {@link #handleExceptionInternal} and get mapped to the correct 4xx status with our envelope —
 * never swallowed into a 500 by the catch-all. Per-type customization is done ONLY via
 * {@code @Override} of the protected hooks; adding a second {@code @ExceptionHandler} for a
 * framework type would crash startup with an ambiguous-mapping error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Domain errors carry their own status, code and (human) message. */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.getStatus())
        .body(ApiError.of(ex.getCode(), ex.getMessage(), request.getRequestURI()));
  }

  /** Genuine last resort: the ONLY handler that returns 500. Logs the stack trace server-side. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception", ex);
    // Never leak internals (messages, stack traces) to the client.
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError.of("INTERNAL_ERROR", "Unexpected server error", request.getRequestURI()));
  }

  /** Single body-rewrite point for every framework exception handled by the base class. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (statusCode.is5xxServerError()) {
      // A framework 5xx (e.g. MissingPathVariable) signals a server/mapping bug — log it.
      log.error("Framework exception mapped to {}", statusCode, ex);
    }
    Object envelope =
        (body instanceof ApiError)
            ? body
            : ApiError.of(codeFor(statusCode), safeMessageFor(statusCode), pathOf(request));
    // Delegate to super so framework headers (Allow/Accept), status and the
    // response-already-committed null guard all survive.
    return super.handleExceptionInternal(ex, envelope, headers, statusCode, request);
  }

  /** Bean-Validation failure → 400 VALIDATION_ERROR listing the offending fields. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<ApiError.FieldViolation> fields =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
            .toList();
    ApiError body = ApiError.validation("Request validation failed", pathOf(request), fields);
    return handleExceptionInternal(ex, body, headers, status, request);
  }

  /** Malformed / unreadable JSON body → 400 MALFORMED_REQUEST. */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ApiError body = ApiError.of("MALFORMED_REQUEST", "Malformed request body", pathOf(request));
    return handleExceptionInternal(ex, body, headers, status, request);
  }

  private static String codeFor(HttpStatusCode statusCode) {
    if (statusCode.is5xxServerError()) {
      return "INTERNAL_ERROR";
    }
    return switch (statusCode.value()) {
      case 404 -> "RESOURCE_NOT_FOUND";
      case 405 -> "METHOD_NOT_ALLOWED";
      case 415 -> "UNSUPPORTED_MEDIA_TYPE";
      case 406 -> "NOT_ACCEPTABLE";
      case 400 -> "BAD_REQUEST";
      default -> deriveCode(statusCode);
    };
  }

  /** Other 4xx: derive a SCREAMING_SNAKE_CASE code from the standard HTTP status name. */
  private static String deriveCode(HttpStatusCode statusCode) {
    HttpStatus resolved = HttpStatus.resolve(statusCode.value());
    return resolved != null ? resolved.name() : "REQUEST_ERROR";
  }

  /** Curated, status-based message — NEVER {@code ex.getMessage()} (it leaks paths/internals). */
  private static String safeMessageFor(HttpStatusCode statusCode) {
    if (statusCode.is5xxServerError()) {
      return "Unexpected server error";
    }
    return switch (statusCode.value()) {
      case 404 -> "Resource not found";
      case 405 -> "HTTP method not allowed";
      case 415 -> "Unsupported media type";
      case 406 -> "Requested representation not available";
      case 400 -> "Bad request";
      default -> "Request could not be processed";
    };
  }

  private static String pathOf(WebRequest request) {
    return ((ServletWebRequest) request).getRequest().getRequestURI();
  }
}
