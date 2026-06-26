package com.ecommerce.payment.client;

import com.ecommerce.payment.exception.ApiException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Order Service. MUST use the auto-configured {@code RestClient.Builder} bean — NOT
 * the static {@code RestClient.builder()} — so that Boot's SNAKE_CASE ObjectMapper is applied.
 * Using the static builder bypasses Boot's customisers and produces camelCase field names, which
 * broke Order in Wave 2 (restclient-snakecase-gotcha).
 */
@Component
public class OrderClient {

  private final RestClient restClient;

  /**
   * @param builder injected Boot-configured builder — do NOT replace with RestClient.builder().
   * @param baseUrl from {@code clients.order.base-url}.
   */
  public OrderClient(
      RestClient.Builder builder, @Value("${clients.order.base-url}") String baseUrl) {
    // clone() keeps all Boot customisers (ObjectMapper, timeouts) and adds our base URL.
    this.restClient = builder.clone().baseUrl(baseUrl).build();
  }

  /**
   * Load the order from Order Service, forwarding the caller's JWT so the order is returned only if
   * the caller owns it (404 otherwise — Order enforces ownership).
   *
   * @param orderId order UUID
   * @param rawToken the raw JWT string (no {@code Bearer } prefix) from {@link
   *     com.ecommerce.payment.security.CurrentUser#bearerToken()}
   * @return order view, never null
   * @throws ApiException 404 ORDER_NOT_FOUND when the order is missing or not the caller's
   */
  public OrderView getOrder(UUID orderId, String rawToken) {
    return restClient
        .get()
        .uri("/api/v1/orders/{id}", orderId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
        .retrieve()
        .onStatus(
            status -> status == HttpStatusCode.valueOf(404),
            (req, res) -> {
              throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
            })
        .onStatus(
            HttpStatusCode::is4xxClientError,
            (req, res) -> {
              throw new ApiException(
                  HttpStatus.BAD_GATEWAY,
                  "PAYMENT_GATEWAY_ERROR",
                  "Upstream order service returned client error");
            })
        .onStatus(
            HttpStatusCode::is5xxServerError,
            (req, res) -> {
              throw new ApiException(
                  HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR", "Order service unavailable");
            })
        .body(OrderView.class);
  }
}
