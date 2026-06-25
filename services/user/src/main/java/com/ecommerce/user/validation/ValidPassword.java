package com.ecommerce.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Password policy: 12-72 chars, at least one letter and at least one digit. */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

  String message() default "must be 12-72 characters and contain at least one letter and one digit";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
