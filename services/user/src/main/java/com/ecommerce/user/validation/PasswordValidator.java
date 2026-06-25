package com.ecommerce.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

  private static final int MIN_LENGTH = 12;
  private static final int MAX_LENGTH = 72;

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return false;
    }
    if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
      return false;
    }
    boolean hasLetter = false;
    boolean hasDigit = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isLetter(c)) {
        hasLetter = true;
      } else if (Character.isDigit(c)) {
        hasDigit = true;
      }
    }
    return hasLetter && hasDigit;
  }
}
