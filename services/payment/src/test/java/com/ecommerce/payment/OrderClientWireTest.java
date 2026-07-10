package com.ecommerce.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.exception.ApiException;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Non-mocked HTTP seam test for the Payment -> Order call. The client under test is the REAL {@link
 * OrderClient} bean, wired by Boot exactly as in production: the auto-configured {@code
 * RestClient.Builder} (SNAKE_CASE ObjectMapper from application.yml) plus the {@code
 * timeoutRestClientCustomizer}. Requests cross a real socket to a WireMock stand-in that speaks the
 * real Order Service wire shape.
 *
 * <p>Because the client is Boot-wired (never hand-built), this doubles as the
 * restclient-snakecase-gotcha regression guard: if {@code OrderClient} regressed to the static
 * {@code RestClient.builder()} or SNAKE_CASE were dropped from {@code application.yml}, {@code
 * userId} would stop binding from {@code user_id} and the happy-path assertion would turn RED. The
 * transport-less {@code MockRestServiceServer} test this replaces could not catch that (it stubbed
 * the JSON itself) — and it hid the {@code id}-vs-{@code order_id} wire-mapping bug this test also
 * pins via {@code orderId()}.
 */
class OrderClientWireTest extends AbstractIntegrationTest {

  /**
   * Real Order Service wire shape (api_contracts.md): the identifier is {@code id} (NOT {@code
   * order_id}), the rest snake_case, plus fields OrderView deliberately ignores (items, subtotal,
   * timestamps) — proving the client tolerates the full payload the way Boot's ObjectMapper does.
   */
  private static final String ORDER_JSON =
      """
      {"id":"%s","user_id":7,"status":"PENDING","currency":"EUR",\
      "items":[{"product_id":42,"product_name":"Black T-Shirt","unit_price":19.99,\
      "quantity":2,"line_total":39.98}],"subtotal":39.98,"total":39.98,\
      "created_at":"2026-06-25T10:30:00Z","updated_at":"2026-06-25T10:30:00Z"}
      """;

  private static WireMockServer orderService;

  // Mocked so context load matches the business tests (no broker); this test exercises only the
  // seam.
  @MockitoBean private OutboxRelay outboxRelay;

  @Autowired private OrderClient orderClient;

  @BeforeAll
  static void startOrderStub() {
    orderService = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    orderService.start();
  }

  @AfterAll
  static void stopOrderStub() {
    if (orderService != null) {
      orderService.stop();
    }
  }

  @BeforeEach
  void resetStubs() {
    orderService.resetAll();
  }

  @DynamicPropertySource
  static void orderClientBaseUrl(DynamicPropertyRegistry registry) {
    // Lazy supplier: resolved at context refresh, after @BeforeAll has started the stub.
    registry.add("clients.order.base-url", () -> orderService.baseUrl());
  }

  private static String rawUserToken() {
    return TestJwt.token("7", List.of("USER"));
  }

  @Test
  void getOrder_realWire_parsesSnakeCaseAndBindsIdToOrderId() {
    UUID orderId = UUID.randomUUID();
    orderService.stubFor(
        get(urlEqualTo("/api/v1/orders/" + orderId))
            .willReturn(okJson(ORDER_JSON.formatted(orderId))));

    OrderView view = orderClient.getOrder(orderId, rawUserToken());

    // Binds from the real wire key "id" (was null before @JsonProperty("id")): the id-mapping
    // guard.
    assertThat(view.orderId()).isEqualTo(orderId);
    // Binds from "user_id": the SNAKE_CASE / Boot-wired-builder guard.
    assertThat(view.userId()).isEqualTo(7L);
    assertThat(view.status()).isEqualTo("PENDING");
    assertThat(view.total()).isEqualByComparingTo("39.98");
    assertThat(view.currency()).isEqualTo("EUR");
  }

  @Test
  void getOrder_forwardsCallersBearerToken() {
    UUID orderId = UUID.randomUUID();
    orderService.stubFor(
        get(urlEqualTo("/api/v1/orders/" + orderId))
            .willReturn(okJson(ORDER_JSON.formatted(orderId))));
    String rawToken = rawUserToken();

    orderClient.getOrder(orderId, rawToken);

    // Assert the emitted value, not just presence: a missing "Bearer " prefix/space must fail.
    orderService.verify(
        getRequestedFor(urlEqualTo("/api/v1/orders/" + orderId))
            .withHeader("Authorization", matching("^Bearer .+$"))
            .withHeader("Authorization", equalTo("Bearer " + rawToken)));
  }

  @Test
  void getOrder_notFound_mapsToOrderNotFound() {
    UUID orderId = UUID.randomUUID();
    orderService.stubFor(
        get(urlEqualTo("/api/v1/orders/" + orderId)).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> orderClient.getOrder(orderId, rawUserToken()))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(ex.getCode()).isEqualTo("ORDER_NOT_FOUND");
            });
  }
}
