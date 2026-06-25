package com.ecommerce.product.exception;

import org.springframework.http.HttpStatus;

public class UnprocessableEntityException extends ApiException {

  public UnprocessableEntityException(String code, String message) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }
}
