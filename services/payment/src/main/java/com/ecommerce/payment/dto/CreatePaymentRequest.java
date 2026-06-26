package com.ecommerce.payment.dto;

import com.ecommerce.payment.validation.NotRawCardData;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import java.util.UUID;

/**
 * Request body for POST /api/v1/payments.
 *
 * <p>Security: raw card data fields are defined here with {@code @Null} so that sending them
 * produces a VALIDATION_ERROR (400) rather than being silently ignored. The {@code @NotRawCardData}
 * constraint on the token rejects a 13–19 digit PAN in the token field. {@code ignoreUnknown=false}
 * rejects any unrecognised field outright.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class CreatePaymentRequest {

  @NotNull private UUID orderId;

  @NotBlank @NotRawCardData private String paymentMethodToken;

  /** Optional; if supplied it must match order.currency or the request is rejected 422. */
  private String currency;

  // --- Card-data trap fields: explicitly mapped so Bean Validation @Null fires if set ---

  @Null(message = "Raw card data is not accepted; use a gateway payment token")
  @JsonProperty("card_number")
  private String cardNumber;

  @Null(message = "Raw card data is not accepted; use a gateway payment token")
  @JsonProperty("pan")
  private String pan;

  @Null(message = "Raw card data is not accepted; use a gateway payment token")
  @JsonProperty("cvv")
  private String cvv;

  @Null(message = "Raw card data is not accepted; use a gateway payment token")
  @JsonProperty("cvc")
  private String cvc;

  public UUID getOrderId() {
    return orderId;
  }

  public String getPaymentMethodToken() {
    return paymentMethodToken;
  }

  public String getCurrency() {
    return currency;
  }
}
