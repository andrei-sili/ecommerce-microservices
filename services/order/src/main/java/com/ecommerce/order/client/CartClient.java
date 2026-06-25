package com.ecommerce.order.client;

import com.ecommerce.order.exception.UpstreamServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * User-scoped calls to the Cart Service: the end-user's {@code Authorization: Bearer <jwt>} is
 * forwarded so Cart authorizes as that user and returns only their own cart.
 */
@Component
public class CartClient {

  private final RestClient restClient;

  public CartClient(RestClient cartRestClient) {
    this.restClient = cartRestClient;
  }

  public CartView getCart(String bearerToken) {
    try {
      return restClient
          .get()
          .uri("/api/v1/cart")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
          .retrieve()
          .body(CartView.class);
    } catch (RestClientException ex) {
      throw new UpstreamServiceException("Cart service is unavailable");
    }
  }

  /** Best-effort cart clear after placement; the caller treats failure as non-fatal. */
  public void clearCart(String bearerToken) {
    restClient
        .delete()
        .uri("/api/v1/cart")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
        .retrieve()
        .toBodilessEntity();
  }
}
