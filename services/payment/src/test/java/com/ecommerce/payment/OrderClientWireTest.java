package com.ecommerce.payment;

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

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import com.ecommerce.payment.config.ClientsConfig;
import com.ecommerce.payment.config.ClientsProperties;
import com.ecommerce.payment.exception.ApiException;
import com.ecommerce.payment.support.TestJwt;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Non-mocked HTTP seam test for the Payment -> Order call: the REAL {@link OrderClient} is
 * exercised over a socket against a WireMock stand-in that speaks the REAL Order Service wire
 * shape. This replaces the former transport-less {@code MockRestServiceServer} test, which stubbed
 * the JSON itself and therefore could not catch a wire-shape mismatch — that mock hid the fact that
 * the Order object serialises its identifier as {@code "id"} (not {@code "order_id"}), so {@code
 * OrderView.orderId()} came back null over the real wire (testing.md: don't mock the seam you don't
 * own).
 *
 * <p>The client is built from the production snake_case ObjectMapper plus the production timeout
 * customiser ({@link ClientsConfig#timeoutRestClientCustomizer}), so the migrated {@code
 * ClientHttpRequestFactoryBuilder} path, the snake_case parsing, the outbound {@code Authorization:
 * Bearer} header, and the 4xx error mapping are all verified against real HTTP behaviour.
 */
@WireMockTest
class OrderClientWireTest {

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

  private static String rawUserToken() {
    return TestJwt.token("7", List.of("USER"));
  }

  private OrderClient clientFor(String baseUrl) {
    RestClient.Builder builder =
        RestClient.builder()
            .messageConverters(
                converters -> {
                  converters.removeIf(OrderClientWireTest::isJacksonConverter);
                  converters.add(new MappingJackson2HttpMessageConverter(snakeCaseMapper()));
                });
    // Exercise the production timeout customiser (the migrated ClientHttpRequestFactoryBuilder
    // path).
    new ClientsConfig().timeoutRestClientCustomizer(new ClientsProperties()).customize(builder);
    return new OrderClient(builder, baseUrl);
  }

  private static boolean isJacksonConverter(HttpMessageConverter<?> converter) {
    return converter instanceof MappingJackson2HttpMessageConverter;
  }

  /** Mirrors Boot's ObjectMapper: SNAKE_CASE naming + tolerant of unmodelled fields. */
  private static ObjectMapper snakeCaseMapper() {
    return JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build();
  }

  @Test
  void getOrder_realWire_parsesSnakeCaseAndBindsIdToOrderId(WireMockRuntimeInfo wm) {
    UUID orderId = UUID.randomUUID();
    stubFor(
        get(urlEqualTo("/api/v1/orders/" + orderId))
            .willReturn(okJson(ORDER_JSON.formatted(orderId))));

    OrderView view = clientFor(wm.getHttpBaseUrl()).getOrder(orderId, rawUserToken());

    // orderId() binds from the real wire key "id" (was null before the @JsonProperty("id") fix).
    assertThat(view.orderId()).isEqualTo(orderId);
    assertThat(view.userId()).isEqualTo(7L);
    assertThat(view.status()).isEqualTo("PENDING");
    assertThat(view.total()).isEqualByComparingTo("39.98");
    assertThat(view.currency()).isEqualTo("EUR");
  }

  @Test
  void getOrder_forwardsCallersBearerToken(WireMockRuntimeInfo wm) {
    UUID orderId = UUID.randomUUID();
    stubFor(
        get(urlEqualTo("/api/v1/orders/" + orderId))
            .willReturn(okJson(ORDER_JSON.formatted(orderId))));
    String rawToken = rawUserToken();

    clientFor(wm.getHttpBaseUrl()).getOrder(orderId, rawToken);

    // Assert the emitted value, not just presence: a missing "Bearer " prefix/space must fail.
    verify(
        getRequestedFor(urlEqualTo("/api/v1/orders/" + orderId))
            .withHeader("Authorization", matching("^Bearer .+$"))
            .withHeader("Authorization", equalTo("Bearer " + rawToken)));
  }

  @Test
  void getOrder_notFound_mapsToOrderNotFound(WireMockRuntimeInfo wm) {
    UUID orderId = UUID.randomUUID();
    stubFor(get(urlEqualTo("/api/v1/orders/" + orderId)).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> clientFor(wm.getHttpBaseUrl()).getOrder(orderId, rawUserToken()))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(ex.getCode()).isEqualTo("ORDER_NOT_FOUND");
            });
  }
}
