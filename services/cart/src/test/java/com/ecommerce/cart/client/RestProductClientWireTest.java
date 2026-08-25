package com.ecommerce.cart.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.cart.config.ProductClientConfig;
import com.ecommerce.cart.exception.ConflictException;
import com.ecommerce.cart.exception.NotFoundException;
import com.ecommerce.cart.exception.ServiceUnavailableException;
import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.ecommerce.cart.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Non-mocked HTTP seam test for the Cart -> Product call. The {@link RestProductClient} is taken
 * from the Spring context, so the {@link RestClient} it holds is the one {@link
 * ProductClientConfig} actually publishes at runtime, wired with the application's own message
 * converters; the call then goes over a real socket to a WireMock stand-in. The {@code
 * ProductClient} interface is never mocked, so the outbound URL, the forwarded {@code
 * Authorization: Bearer} header, the snake_case response parsing and the error mapping are all
 * verified against real wire behaviour.
 *
 * <p>Cart has no {@code src/test/resources}, so the context here reads the SHIPPED {@code
 * application.yml} — the parsing rows below are evidence about the production client, not about a
 * test fixture.
 */
class RestProductClientWireTest extends AbstractIntegrationTest {

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  @BeforeAll
  static void startWireMock() {
    if (!WIREMOCK.isRunning()) {
      WIREMOCK.start();
    }
  }

  @AfterAll
  static void stopWireMock() {
    WIREMOCK.stop();
  }

  @DynamicPropertySource
  static void productServiceUrl(DynamicPropertyRegistry registry) {
    registry.add("product.service.base-url", WIREMOCK::baseUrl);
  }

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

  @Autowired private RestProductClient productClient;
  @Autowired private RestClient productRestClient;

  @BeforeEach
  void resetStubs() {
    WIREMOCK.resetAll();
  }

  private static String userBearer() {
    return TestJwt.bearer(TestJwt.token("42", List.of("USER")));
  }

  @Test
  void forwardsCallersBearerTokenOnProductLookup() {
    WIREMOCK.stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));
    String bearer = userBearer();

    productClient.fetchAvailableProduct(42, bearer);

    // Assert the emitted value, not just presence: a missing "Bearer " prefix must fail.
    WIREMOCK.verify(
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
  void productLookup_isExactlyOneBodylessGet() {
    WIREMOCK.stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));

    productClient.fetchAvailableProduct(42, userBearer());

    WIREMOCK.verify(
        1, getRequestedFor(urlEqualTo("/api/v1/products/42")).withoutHeader("Content-Type"));
    assertThat(
            WIREMOCK
                .findAll(getRequestedFor(urlEqualTo("/api/v1/products/42")))
                .get(0)
                .getBodyAsString())
        .isEmpty();
  }

  @Test
  void parsesSnakeCaseProductIntoSnapshot() {
    WIREMOCK.stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));

    ProductSnapshot snapshot = productClient.fetchAvailableProduct(42, userBearer());

    // Whole-record equality pins the FULL property set: a component that changes value, gains a
    // null, or loses its scale fails here, where per-field assertions would each still pass.
    assertThat(snapshot).isEqualTo(EXPECTED_SNAPSHOT);
    assertThat(snapshot.unitPrice().scale()).isEqualTo(2);
  }

  /**
   * The discriminating row for the client's casing configuration (B14). Every field the snapshot
   * carries is a single token, spelled identically under either convention, so the snapshot rows
   * above stay green on a camelCase-defaulted client. {@code stock_quantity} is the one multi-token
   * field in the Product contract: it binds only when the client was built from Boot's configured
   * builder, and is silently dropped by the static factory method on {@link RestClient}.
   */
  @Test
  void snakeCaseMultiTokenField_bindsThroughTheContextConfiguredClient() {
    WIREMOCK.stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(PRODUCT_JSON)));

    ProductView view = fetchProductView(42);

    assertThat(view.stockQuantity())
        .as("stock_quantity must bind — the client must use the SNAKE_CASE-configured converters")
        .isEqualTo(120);
  }

  /**
   * The negative half of the same control: a peer that sent {@code stockQuantity} would be speaking
   * the wrong convention, and the value must NOT bind. Without this row the positive assertion
   * above is also satisfied by a deserializer that accepts both spellings, which is a different
   * contract.
   */
  @Test
  void camelCaseMultiTokenField_doesNotBind_provingTheStrategyIsNotCaseAgnostic() {
    String camelCaseBody = PRODUCT_JSON.replace("\"stock_quantity\"", "\"stockQuantity\"");
    WIREMOCK.stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(camelCaseBody)));

    ProductView view = fetchProductView(42);

    assertThat(view.stockQuantity())
        .as("a camelCase key must be ignored; accepting both spellings is not the pinned contract")
        .isNull();
    assertThat(view.name()).isEqualTo("Black T-Shirt");
  }

  /**
   * The Product contract is allowed to grow fields. Deserialization must ignore them and produce a
   * snapshot EQUAL to the one built from the lean body — the assertion a {@code contains}-style
   * check cannot make, because it passes precisely when extra fields appear.
   */
  @Test
  void unknownResponseFields_areIgnored_snapshotStaysExact() {
    String withExtraFields =
        PRODUCT_JSON.replace(
            "\"available\": true",
            """
            "available": true,
                "discount_percent": 15,
                "warehouse": { "id": 9, "code": "EU-WEST" }""");
    WIREMOCK.stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(withExtraFields)));

    ProductSnapshot snapshot = productClient.fetchAvailableProduct(42, userBearer());

    assertThat(snapshot).isEqualTo(EXPECTED_SNAPSHOT);
  }

  @Test
  void mapsNotFoundToProductNotFound() {
    WIREMOCK.stubFor(
        get(urlEqualTo("/api/v1/products/999")).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> productClient.fetchAvailableProduct(999, userBearer()))
        .isInstanceOfSatisfying(
            NotFoundException.class, ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_FOUND"));
  }

  @Test
  void mapsUnavailableProductToConflict() {
    String unavailable = PRODUCT_JSON.replace("\"available\": true", "\"available\": false");
    WIREMOCK.stubFor(get(urlEqualTo("/api/v1/products/42")).willReturn(okJson(unavailable)));

    assertThatThrownBy(() -> productClient.fetchAvailableProduct(42, userBearer()))
        .isInstanceOfSatisfying(
            ConflictException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_UNAVAILABLE"));
  }

  @Test
  void mapsConnectionFaultToServiceUnavailable() {
    WIREMOCK.stubFor(
        get(urlEqualTo("/api/v1/products/42"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThatThrownBy(() -> productClient.fetchAvailableProduct(42, userBearer()))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  /**
   * Reads the raw projection off the SAME {@link RestClient} bean {@link RestProductClient} is
   * constructed with, since the snapshot the client returns deliberately drops the field under
   * test.
   */
  private ProductView fetchProductView(long productId) {
    return productRestClient
        .get()
        .uri("/api/v1/products/{id}", productId)
        .header("Authorization", userBearer())
        .retrieve()
        .body(ProductView.class);
  }
}
