package com.ecommerce.payment.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class NotRawCardDataValidator implements ConstraintValidator<NotRawCardData, String> {

  // Detects a contiguous run of 13–19 digits — matches all common PAN formats.
  private static final Pattern RAW_PAN_PATTERN = Pattern.compile("\\d{13,19}");

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    return !RAW_PAN_PATTERN.matcher(value).find();
  }
}
