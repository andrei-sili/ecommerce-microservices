package com.ecommerce.order.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  /** Insufficient stock carries the offending product id (contract: body field {@code product_id}). */
  @ExceptionHandler(InsufficientStockException.class)
  public ResponseEntity<ErrorResponse> handleInsufficientStock(
      InsufficientStockException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.getStatus())
        .body(
            ErrorResponse.withProduct(
                ex.getCode(), ex.getMessage(), request.getRequestURI(), ex.getProductId()));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.getStatus())
        .body(ErrorResponse.of(ex.getCode(), ex.getMessage(), request.getRequestURI()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ErrorResponse> handleMissingHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    String code =
        "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
            ? "IDEMPOTENCY_KEY_REQUIRED"
            : "MISSING_HEADER";
    return ResponseEntity.badRequest()
        .body(
            ErrorResponse.of(
                code,
                "Required header '" + ex.getHeaderName() + "' is missing",
                request.getRequestURI()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    String details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::formatFieldError)
            .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest()
        .body(ErrorResponse.of("VALIDATION_ERROR", details, request.getRequestURI()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ErrorResponse> handleMalformed(Exception ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            ErrorResponse.of(
                "MALFORMED_REQUEST",
                "Request body or parameter is malformed",
                request.getRequestURI()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI()));
  }

  private static String formatFieldError(FieldError error) {
    return error.getField() + " " + error.getDefaultMessage();
  }
}
