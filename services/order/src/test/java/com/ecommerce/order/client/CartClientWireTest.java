package com.ecommerce.order.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.order.exception.UpstreamServiceException;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Non-mocked HTTP seam test for the Order -> Cart call. The client under test is the REAL {@link
 * CartClient} bean, Boot-wired exactly as in production (auto-configured {@code RestClient.Builder}
 * carrying the SNAKE_CASE ObjectMapper from application.yml + the migrated {@code
 * timeoutRestClientCustomizer}). Requests cross a real socket to a WireMock stand-in that speaks
 * the real Cart Service wire shape.
 *
 * <p>Because the client is Boot-wired (never hand-built) and travels the real transport, this is
 * the regression guard the transport-less {@code MockRestServiceServer} predecessor could not be: a
 * dropped {@code Bearer} prefix, a camelCase serialization default, or a broken request factory
 * would turn these assertions RED instead of hiding behind a stubbed JSON body.
 */
class CartClientWireTest extends AbstractIntegrationTest {

  /**
   * Cart object from api_contracts.md, verbatim snake_case, carrying the full payload including the
   * fields {@link CartView} deliberately ignores (product_name, snapshot_at, total_items,
   * updated_at) — proving the client tolerates the whole wire body the way Boot's ObjectMapper
   * does.
   */
  private static final String CART_JSON =
      """
      {"user_id":7,"currency":"EUR",\
      "items":[{"product_id":42,"product_name":"Black T-Shirt","unit_price":19.99,\
      "quantity":2,"line_total":39.98,"snapshot_at":"2026-06-25T10:30:00Z"}],\
      "subtotal":39.98,"total_items":2,"updated_at":"2026-06-25T10:30:00Z"}
      """;

  private static WireMockServer cartService;

  @Autowired private CartClient cartClient;

  @BeforeAll
  static void startCartStub() {
    cartService = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    cartService.start();
  }

  @AfterAll
  static void stopCartStub() {
    if (cartService != null) {
      cartService.stop();
    }
  }

  @BeforeEach
  void resetStubs() {
    cartService.resetAll();
  }

  @DynamicPropertySource
  static void cartBaseUrl(DynamicPropertyRegistry registry) {
    // Lazy supplier: resolved at context refresh, after @BeforeAll has started the stub.
    registry.add("clients.cart.base-url", () -> cartService.baseUrl());
  }

  private static String rawUserToken() {
    return TestJwt.token("7", List.of("USER"));
  }

  @Test
  void getCart_realWire_parsesSnakeCaseIntoCartView() {
    cartService.stubFor(get(urlEqualTo("/api/v1/cart")).willReturn(okJson(CART_JSON)));

    CartView cart = cartClient.getCart(rawUserToken());

    // user_id is multi-word: binds only if the SNAKE_CASE mapper is really in effect.
    assertThat(cart.userId()).isEqualTo(7L);
    assertThat(cart.currency()).isEqualTo("EUR");
    assertThat(cart.subtotal()).isEqualByComparingTo("39.98");
    assertThat(cart.isEmpty()).isFalse();
    assertThat(cart.items()).hasSize(1);
    assertThat(cart.items().get(0).productId()).isEqualTo(42L);
    assertThat(cart.items().get(0).quantity()).isEqualTo(2);
  }

  @Test
  void getCart_forwardsRawJwtAsBearer() {
    cartService.stubFor(get(urlEqualTo("/api/v1/cart")).willReturn(okJson(CART_JSON)));
    String rawToken = rawUserToken();

    cartClient.getCart(rawToken);

    // The client prepends "Bearer " to the RAW jwt: assert the emitted value, not just presence.
    cartService.verify(
        getRequestedFor(urlEqualTo("/api/v1/cart"))
            .withHeader("Authorization", matching("^Bearer .+$"))
            .withHeader("Authorization", equalTo("Bearer " + rawToken)));
  }

  @Test
  void clearCart_forwardsRawJwtAsBearer() {
    cartService.stubFor(delete(urlEqualTo("/api/v1/cart")).willReturn(aResponse().withStatus(204)));
    String rawToken = rawUserToken();

    cartClient.clearCart(rawToken);

    cartService.verify(
        deleteRequestedFor(urlEqualTo("/api/v1/cart"))
            .withHeader("Authorization", matching("^Bearer .+$"))
            .withHeader("Authorization", equalTo("Bearer " + rawToken)));
  }

  @Test
  void getCart_upstreamFault_mapsToUpstreamServiceException() {
    cartService.stubFor(
        get(urlEqualTo("/api/v1/cart"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThatThrownBy(() -> cartClient.getCart(rawUserToken()))
        .isInstanceOf(UpstreamServiceException.class)
        .hasMessage("Cart service is unavailable");
  }
}
