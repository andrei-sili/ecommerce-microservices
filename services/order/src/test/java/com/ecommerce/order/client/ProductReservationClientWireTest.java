package com.ecommerce.order.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.order.exception.InsufficientStockException;
import com.ecommerce.order.exception.UpstreamServiceException;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Non-mocked HTTP seam test for the Order -> Product reservation calls (reserve + release). The
 * client under test is the REAL {@link ProductReservationClient} bean, Boot-wired exactly as in
 * production, exercised over a real socket against a WireMock stand-in speaking the contract wire
 * shape. This is the guard the transport-less {@code MockRestServiceServer} predecessor could not
 * be: the outbound snake_case body, the {@code X-Internal-Api-Key} header, and the upstream
 * status-to-exception mapping are all verified against real wire behavior.
 */
class ProductReservationClientWireTest extends AbstractIntegrationTest {

  private static final UUID ORDER_ID = UUID.fromString("9f1c2e7a-0000-0000-0000-000000000001");
  private static final String RESERVATIONS_URL = "/api/v1/inventory/reservations";

  /**
   * 201 reserve response from api_contracts.md, verbatim snake_case with the full payload (product
   * name, per-line currency, line_total) — the client must bind these into the camelCase record.
   */
  private static final String RESERVED_JSON =
      """
      {"order_id":"9f1c2e7a-0000-0000-0000-000000000001","status":"RESERVED",\
      "items":[{"product_id":42,"name":"Black T-Shirt","unit_price":19.99,\
      "currency":"EUR","quantity":2,"line_total":39.98}],\
      "currency":"EUR","subtotal":39.98}
      """;

  private static WireMockServer productService;

  @Autowired private ProductReservationClient reservationClient;

  @BeforeAll
  static void startProductStub() {
    productService = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    productService.start();
  }

  @AfterAll
  static void stopProductStub() {
    if (productService != null) {
      productService.stop();
    }
  }

  @BeforeEach
  void resetStubs() {
    productService.resetAll();
  }

  @DynamicPropertySource
  static void productBaseUrl(DynamicPropertyRegistry registry) {
    // Lazy supplier: resolved at context refresh, after @BeforeAll has started the stub.
    registry.add("clients.product.base-url", () -> productService.baseUrl());
  }

  private static ReservationRequest oneLine() {
    return new ReservationRequest(ORDER_ID, List.of(new ReservationRequest.Line(42L, 2)));
  }

  @Test
  void reserve_realWire_serializesSnakeCaseBody_andParsesResponse() {
    productService.stubFor(
        post(urlEqualTo(RESERVATIONS_URL))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(RESERVED_JSON)));

    ReservationResponse response = reservationClient.reserve(ORDER_ID, oneLine());

    assertThat(response.orderId()).isEqualTo(ORDER_ID);
    assertThat(response.status()).isEqualTo("RESERVED");
    assertThat(response.currency()).isEqualTo("EUR");
    assertThat(response.subtotal()).isEqualByComparingTo("39.98");
    assertThat(response.items()).hasSize(1);
    ReservationResponse.Item item = response.items().get(0);
    assertThat(item.productId()).isEqualTo(42L);
    assertThat(item.name()).isEqualTo("Black T-Shirt");
    assertThat(item.unitPrice()).isEqualByComparingTo("19.99");
    assertThat(item.currency()).isEqualTo("EUR");
    assertThat(item.quantity()).isEqualTo(2);
    assertThat(item.lineTotal()).isEqualByComparingTo("39.98");

    // Outbound body MUST be snake_case (contract) and carry the internal system key.
    productService.verify(
        postRequestedFor(urlEqualTo(RESERVATIONS_URL))
            .withHeader("X-Internal-Api-Key", equalTo("test-internal-api-key"))
            .withRequestBody(matchingJsonPath("$.order_id", equalTo(ORDER_ID.toString())))
            .withRequestBody(matchingJsonPath("$.items[0].product_id", equalTo("42")))
            .withRequestBody(matchingJsonPath("$.items[0].quantity", equalTo("2")))
            // The camelCase keys must NOT appear on the wire.
            .withRequestBody(notMatching("(?s).*\"orderId\".*"))
            .withRequestBody(notMatching("(?s).*\"productId\".*")));

    // The transport itself is contract here (E7-4). The JDK client defaults to HTTP/2 and attempts
    // an h2c upgrade on cleartext http://, which WireMock advertises — that combination fails a
    // POST-with-body with a transport EOF, and order carries the fleet's only HTTP/1.1 pin because
    // of it. Deleting the pin does turn this class red, but as a generic "service is unavailable":
    // asserting the negotiated protocol names the cause instead of leaving it to be re-diagnosed.
    assertThat(productService.getAllServeEvents())
        .singleElement()
        .satisfies(
            event ->
                assertThat(event.getRequest().getProtocol())
                    .as("outbound reservation POST must negotiate HTTP/1.1, never h2c")
                    .isEqualTo("HTTP/1.1"));
  }

  @Test
  void reserve_conflict_mapsToInsufficientStockWithProductId() {
    String body =
        """
        {"error":"INSUFFICIENT_STOCK","message":"Only 1 left of Black T-Shirt",\
        "product_id":42,"timestamp":"2026-06-25T10:30:00Z",\
        "path":"/api/v1/inventory/reservations"}
        """;
    productService.stubFor(
        post(urlEqualTo(RESERVATIONS_URL))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));

    assertThatThrownBy(() -> reservationClient.reserve(ORDER_ID, oneLine()))
        .isInstanceOfSatisfying(
            InsufficientStockException.class,
            ex -> {
              assertThat(ex.getProductId()).isEqualTo(42L);
              assertThat(ex.getMessage()).isEqualTo("Only 1 left of Black T-Shirt");
            });
  }

  @Test
  void reserve_unprocessable_mapsToReservationRejectedWithRelayedCode() {
    String body =
        """
        {"error":"MIXED_CURRENCY_CART","message":"All lines must share one currency"}
        """;
    productService.stubFor(
        post(urlEqualTo(RESERVATIONS_URL))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));

    assertThatThrownBy(() -> reservationClient.reserve(ORDER_ID, oneLine()))
        .isInstanceOfSatisfying(
            ProductReservationClient.ReservationRejectedException.class,
            ex -> {
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(ex.getCode()).isEqualTo("MIXED_CURRENCY_CART");
              assertThat(ex.getMessage()).isEqualTo("All lines must share one currency");
            });
  }

  @Test
  void reserve_serverError_mapsToUpstreamServiceException() {
    productService.stubFor(
        post(urlEqualTo(RESERVATIONS_URL)).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> reservationClient.reserve(ORDER_ID, oneLine()))
        .isInstanceOf(UpstreamServiceException.class)
        .hasMessage("Product reservation failed");
  }

  @Test
  void release_realWire_sendsInternalKey_andAccepts204() {
    productService.stubFor(
        delete(urlEqualTo(RESERVATIONS_URL + "/" + ORDER_ID))
            .willReturn(aResponse().withStatus(204)));

    reservationClient.release(ORDER_ID); // must not throw

    productService.verify(
        deleteRequestedFor(urlEqualTo(RESERVATIONS_URL + "/" + ORDER_ID))
            .withHeader("X-Internal-Api-Key", equalTo("test-internal-api-key")));
  }

  @Test
  void release_connectionFault_mapsToUpstreamServiceException() {
    productService.stubFor(
        delete(urlEqualTo(RESERVATIONS_URL + "/" + ORDER_ID))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThatThrownBy(() -> reservationClient.release(ORDER_ID))
        .isInstanceOf(UpstreamServiceException.class)
        .hasMessage("Product service is unavailable");
  }
}
