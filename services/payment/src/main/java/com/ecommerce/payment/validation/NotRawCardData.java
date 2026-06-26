package com.ecommerce.payment.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Rejects values that look like raw card data (a 13–19 digit PAN run). */
@Constraint(validatedBy = NotRawCardDataValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotRawCardData {
  String message() default "Raw card data is not accepted; use a gateway payment token";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
