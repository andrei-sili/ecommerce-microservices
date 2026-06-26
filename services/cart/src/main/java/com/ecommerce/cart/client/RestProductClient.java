package com.ecommerce.cart.client;

import com.ecommerce.cart.exception.ConflictException;
import com.ecommerce.cart.exception.NotFoundException;
import com.ecommerce.cart.exception.ServiceUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class RestProductClient implements ProductClient {

  private final RestClient restClient;

  public RestProductClient(RestClient productRestClient) {
    this.restClient = productRestClient;
  }

  @Override
  public ProductSnapshot fetchAvailableProduct(long productId, String bearerToken) {
    ProductView product = requestProduct(productId, bearerToken);
    if (product == null || !Boolean.TRUE.equals(product.available())) {
      // A null body or available==false means the product cannot be bought right now.
      if (product != null && Boolean.FALSE.equals(product.available())) {
        throw new ConflictException("PRODUCT_UNAVAILABLE", "Product is not available for purchase");
      }
      throw new NotFoundException("PRODUCT_NOT_FOUND", "Product not found");
    }
    return new ProductSnapshot(
        productId, product.name(), product.price(), normalizeCurrency(product.currency()));
  }

  private ProductView requestProduct(long productId, String bearerToken) {
    try {
      return restClient
          .get()
          .uri("/api/v1/products/{id}", productId)
          .headers(
              headers -> {
                if (StringUtils.hasText(bearerToken)) {
                  headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
                }
              })
          .retrieve()
          .onStatus(
              status -> status.value() == HttpStatus.NOT_FOUND.value(),
              (request, response) -> {
                throw new NotFoundException("PRODUCT_NOT_FOUND", "Product not found");
              })
          .body(ProductView.class);
    } catch (NotFoundException | ConflictException ex) {
      throw ex;
    } catch (ResourceAccessException ex) {
      // Connection refused / timeout — surface a clean 503, never the underlying cause.
      throw new ServiceUnavailableException(
          "PRODUCT_SERVICE_UNAVAILABLE", "Product service is temporarily unavailable");
    } catch (RuntimeException ex) {
      throw new ServiceUnavailableException(
          "PRODUCT_SERVICE_ERROR", "Could not validate the product right now");
    }
  }

  private String normalizeCurrency(String currency) {
    return currency == null ? null : currency.trim();
  }
}
