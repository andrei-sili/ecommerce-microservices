package com.ecommerce.order.client;

import com.ecommerce.order.config.ClientsProperties;
import com.ecommerce.order.exception.ApiException;
import com.ecommerce.order.exception.InsufficientStockException;
import com.ecommerce.order.exception.UpstreamServiceException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * System calls to Product's internal inventory reservation API, authenticated with the shared
 * {@code INTERNAL_API_KEY} (header {@code X-Internal-Api-Key}) — never a user JWT and never the
 * ADMIN role. Reserve is all-or-nothing and idempotent on {@code orderId}; release is idempotent.
 */
@Component
public class ProductReservationClient {

  private static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

  private final RestClient restClient;
  private final String internalApiKey;
  private final ObjectMapper objectMapper;

  public ProductReservationClient(
      RestClient productRestClient, ClientsProperties properties, ObjectMapper objectMapper) {
    this.restClient = productRestClient;
    this.internalApiKey = properties.getProduct().getInternalApiKey();
    this.objectMapper = objectMapper;
  }

  /**
   * Reserve stock and reprice in one call. Maps a {@code 409} from Product to a client-facing
   * {@link InsufficientStockException} carrying the offending product id; other upstream failures
   * become {@link UpstreamServiceException}. No internal detail is propagated.
   */
  public ReservationResponse reserve(UUID orderId, ReservationRequest request) {
    try {
      return restClient
          .post()
          .uri("/api/v1/inventory/reservations")
          .header(INTERNAL_KEY_HEADER, internalApiKey)
          .body(request)
          .retrieve()
          .onStatus(
              status -> status.value() == HttpStatus.CONFLICT.value(),
              (req, res) -> {
                UpstreamError error = readError(res.getBody());
                throw new InsufficientStockException(
                    error == null ? null : error.productId(),
                    error == null || error.message() == null
                        ? "Insufficient stock for one or more items"
                        : error.message());
              })
          .onStatus(
              status -> status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value(),
              (req, res) -> {
                UpstreamError error = readError(res.getBody());
                throw new ReservationRejectedException(
                    error == null || error.error() == null ? "RESERVATION_REJECTED" : error.error(),
                    error == null || error.message() == null
                        ? "Reservation could not be processed"
                        : error.message());
              })
          .onStatus(
              status -> status.isError(),
              (req, res) -> {
                throw new UpstreamServiceException("Product reservation failed");
              })
          .body(ReservationResponse.class);
    } catch (ApiException ex) {
      throw ex;
    } catch (RestClientException ex) {
      throw new UpstreamServiceException("Product service is unavailable");
    }
  }

  /**
   * Commit a reservation after payment succeeds: converts the held stock into a real sale ({@code
   * stock_quantity -= qty}, {@code reserved_quantity -= qty}). Idempotent if already committed. If
   * the hold expired before payment landed, Product returns {@code 409 RESERVATION_NOT_ACTIVE}; the
   * caller must handle that as a reconciliation case — never oversell.
   */
  public void commit(UUID orderId) {
    try {
      restClient
          .post()
          .uri("/api/v1/inventory/reservations/{orderId}/commit", orderId)
          .header(INTERNAL_KEY_HEADER, internalApiKey)
          .retrieve()
          .onStatus(
              status -> status.value() == HttpStatus.CONFLICT.value(),
              (req, res) -> {
                throw new ReservationNotActiveException();
              })
          .onStatus(
              status -> status.isError(),
              (req, res) -> {
                throw new UpstreamServiceException("Product commit failed");
              })
          .toBodilessEntity();
    } catch (ApiException ex) {
      throw ex;
    } catch (RestClientException ex) {
      throw new UpstreamServiceException("Product service is unavailable");
    }
  }

  /**
   * Release a reservation (cancellation / placement compensation). Idempotent: a 2xx/204 means
   * done.
   */
  public void release(UUID orderId) {
    try {
      restClient
          .delete()
          .uri("/api/v1/inventory/reservations/{orderId}", orderId)
          .header(INTERNAL_KEY_HEADER, internalApiKey)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException ex) {
      throw new UpstreamServiceException("Product service is unavailable");
    }
  }

  private UpstreamError readError(java.io.InputStream body) {
    try {
      return objectMapper.readValue(body, UpstreamError.class);
    } catch (JacksonException ex) {
      return null;
    }
  }

  /**
   * A 422 from the reservation API (e.g. PRODUCT_NOT_FOUND, MIXED_CURRENCY_CART), relayed as-is.
   */
  public static class ReservationRejectedException extends ApiException {
    public ReservationRejectedException(String code, String message) {
      super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
  }

  /**
   * Commit returned {@code 409 RESERVATION_NOT_ACTIVE}: hold expired before payment landed. The
   * caller must treat this as a permanent reconciliation case — do not oversell.
   */
  public static class ReservationNotActiveException extends ApiException {
    public ReservationNotActiveException() {
      super(HttpStatus.CONFLICT, "RESERVATION_NOT_ACTIVE", "Reservation is not active");
    }
  }
}
