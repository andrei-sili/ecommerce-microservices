package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecommerce.user.validation.PasswordValidator;
import org.junit.jupiter.api.Test;

class PasswordValidatorTest {

  private final PasswordValidator validator = new PasswordValidator();

  @Test
  void acceptsCompliantPassword() {
    assertTrue(validator.isValid("Sup3rSecret12", null));
  }

  @Test
  void rejectsTooShort() {
    assertFalse(validator.isValid("Abc123", null));
  }

  @Test
  void rejectsNoDigit() {
    assertFalse(validator.isValid("OnlyLettersHere", null));
  }

  @Test
  void rejectsNoLetter() {
    assertFalse(validator.isValid("123456789012", null));
  }

  @Test
  void rejectsTooLong() {
    assertFalse(validator.isValid("a1".repeat(40), null));
  }

  @Test
  void rejectsNull() {
    assertFalse(validator.isValid(null, null));
  }
}
