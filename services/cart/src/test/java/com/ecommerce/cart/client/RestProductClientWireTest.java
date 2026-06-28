package com.ecommerce.cart.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.cart.config.ProductClientConfig;
import com.ecommerce.cart.config.ProductClientProperties;
import com.ecommerce.cart.exception.ConflictException;
import com.ecommerce.cart.exception.NotFoundException;
import com.ecommerce.cart.exception.ServiceUnavailableException;
import com.ecommerce.cart.support.TestJwt;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Non-mocked HTTP seam test for the Cart -> Product call: the REAL {@link RestProductClient} (built
 * from the production {@link ProductClientConfig}) is exercised over a socket against a WireMock
 * stand-in. The {@code ProductClient} interface is never mocked, so the outbound URL, the forwarded
 * {@code Authorization: Bearer} header, the snake_case response parsing, and the error mapping are
 * all verified against real wire behavior -- the parts a method-level stub would hide.
 */
@WireMockTest
class RestProductClientWireTest {

  /** Product Service response shape from api_contracts.md (snake_case, nested category, extras). */
  private static final String PRODUCT_JSON =
      """
      {
        "id": 42,
        "sku": "TSHIRT-BLK-M",
        "name": "Black T-Shirt",
        "description": "Soft cotton tee",
        "price": 19.99,
        "currency": "EUR",
        "category": { "id": 3, "name": "Apparel", "slug": "apparel" },
        "stock_quantity": 120,
        "available": true,
        "created_at": "2026-06-25T10:30:00Z",
        "updated_at": "2026-06-25T10:30:00Z"
      }
      """;

  private RestProductClient clientFor(String baseUrl) {
    ProductClientProperties properties = new ProductClientProperties(baseUrl, 2000, 3000);
    RestClient restClient = new ProductClientConfig().productRestClient(properties);
    return new RestProductClient(restClient);
  }

  private static String userBearer() {
    return TestJwt.bearer(TestJwt.token("42", List.of("USER")));
  }

  @Test
  void forwardsCallersBearerTokenOnProductLookup(WireMockRuntimeInfo wm) {
    stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));
    String bearer = userBearer();

    clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(42, bearer);

    // Assert the emitted value, not just presence: a missing "Bearer " prefix must fail.
    verify(
        getRequestedFor(urlEqualTo("/api/v1/products/42"))
            .withHeader("Authorization", matching("^Bearer .+$"))
            .withHeader("Authorization", equalTo(bearer)));
  }

  @Test
  void parsesSnakeCaseProductIntoSnapshot(WireMockRuntimeInfo wm) {
    stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));

    ProductSnapshot snapshot =
        clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(42, userBearer());

    assertThat(snapshot.productId()).isEqualTo(42L);
    assertThat(snapshot.productName()).isEqualTo("Black T-Shirt");
    assertThat(snapshot.unitPrice()).isEqualByComparingTo("19.99");
    assertThat(snapshot.currency()).isEqualTo("EUR");
  }

  @Test
  void mapsNotFoundToProductNotFound(WireMockRuntimeInfo wm) {
    stubFor(get(urlEqualTo("/api/v1/products/999")).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(
            () -> clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(999, userBearer()))
        .isInstanceOfSatisfying(
            NotFoundException.class, ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
  }

  @Test
  void mapsUnavailableProductToConflict(WireMockRuntimeInfo wm) {
    String unavailable = PRODUCT_JSON.replace("\"available\": true", "\"available\": false");
    stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(unavailable)));

    assertThatThrownBy(() -> clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(42, userBearer()))
        .isInstanceOfSatisfying(
            ConflictException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_UNAVAILABLE"));
  }

  @Test
  void mapsConnectionFaultToServiceUnavailable(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo("/api/v1/products/42"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThatThrownBy(() -> clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(42, userBearer()))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
