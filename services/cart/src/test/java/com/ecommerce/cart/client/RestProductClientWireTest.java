package com.ecommerce.cart.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
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
import java.math.BigDecimal;
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

  /** The one snapshot the wire must produce for {@link #PRODUCT_JSON} — compared as a whole. */
  private static final ProductSnapshot EXPECTED_SNAPSHOT =
      new ProductSnapshot(42L, "Black T-Shirt", new BigDecimal("19.99"), "EUR");

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

  /**
   * Exact request-byte pin. The lookup is a bodyless GET, so "exact body equality" here means the
   * body is exactly empty and no {@code Content-Type} is negotiated; a serialization default that
   * starts attaching a request body would still satisfy a substring assertion, and would still be a
   * contract break at the Product boundary.
   */
  @Test
  void productLookup_isExactlyOneBodylessGet(WireMockRuntimeInfo wm) {
    stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));

    clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(42, userBearer());

    verify(1, getRequestedFor(urlEqualTo("/api/v1/products/42")).withoutHeader("Content-Type"));
    assertThat(findAll(getRequestedFor(urlEqualTo("/api/v1/products/42"))).get(0).getBodyAsString())
        .isEmpty();
  }

  @Test
  void parsesSnakeCaseProductIntoSnapshot(WireMockRuntimeInfo wm) {
    stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));

    ProductSnapshot snapshot =
        clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(42, userBearer());

    // Whole-record equality pins the FULL property set: a component that changes value, gains a
    // null, or loses its scale fails here, where per-field assertions would each still pass.
    assertThat(snapshot).isEqualTo(EXPECTED_SNAPSHOT);
    assertThat(snapshot.unitPrice().scale()).isEqualTo(2);
  }

  /**
   * The Product contract is allowed to grow fields. Deserialization must ignore them and produce a
   * snapshot EQUAL to the one built from the lean body — the assertion a {@code contains}-style
   * check cannot make, because it passes precisely when extra fields appear.
   */
  @Test
  void unknownResponseFields_areIgnored_snapshotStaysExact(WireMockRuntimeInfo wm) {
    String withExtraFields =
        PRODUCT_JSON.replace(
            "\"available\": true",
            """
            "available": true,
                "discount_percent": 15,
                "warehouse": { "id": 9, "code": "EU-WEST" }""");
    stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(withExtraFields)));

    ProductSnapshot snapshot =
        clientFor(wm.getHttpBaseUrl()).fetchAvailableProduct(42, userBearer());

    assertThat(snapshot).isEqualTo(EXPECTED_SNAPSHOT);
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
