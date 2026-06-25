package com.ecommerce.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ProductRequest(
    @NotBlank @Size(max = 64) String sku,
    @NotBlank @Size(min = 1, max = 200) String name,
    @Size(max = 4000) String description,
    @NotNull @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal price,
    @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO-4217 code") String currency,
    @NotNull Long categoryId,
    @NotNull @Min(0) Integer stockQuantity) {

  public String currencyOrDefault() {
    return (currency == null || currency.isBlank()) ? "EUR" : currency;
  }
}
