package com.ecommerce.order.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.order.support.AbstractIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
 * Non-mocked HTTP seam test for the Order -> Product commit call. The REAL {@link
 * ProductReservationClient} bean is exercised over a socket against a WireMock stand-in: the commit
 * URL, method, and {@code X-Internal-Api-Key} header are asserted on the emitted request, and a
 * {@code 409 RESERVATION_NOT_ACTIVE} is mapped to {@link
 * ProductReservationClient.ReservationNotActiveException}.
 */
class ProductReservationCommitWireTest extends AbstractIntegrationTest {

  private static final UUID ORDER_ID = UUID.fromString("9f1c2e7a-0000-0000-0000-000000000042");

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

  private static String commitUrl(UUID orderId) {
    return "/api/v1/inventory/reservations/" + orderId + "/commit";
  }

  @Test
  void commit_realWire_sendsInternalKey_andAccepts200() {
    productService.stubFor(
        post(urlEqualTo(commitUrl(ORDER_ID))).willReturn(aResponse().withStatus(200)));

    reservationClient.commit(ORDER_ID); // must not throw

    productService.verify(
        postRequestedFor(urlEqualTo(commitUrl(ORDER_ID)))
            .withHeader("X-Internal-Api-Key", equalTo("test-internal-api-key")));
  }

  @Test
  void commit_conflict_mapsToReservationNotActive() {
    productService.stubFor(
        post(urlEqualTo(commitUrl(ORDER_ID))).willReturn(aResponse().withStatus(409)));

    assertThatThrownBy(() -> reservationClient.commit(ORDER_ID))
        .isInstanceOfSatisfying(
            ProductReservationClient.ReservationNotActiveException.class,
            ex -> {
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(ex.getCode()).isEqualTo("RESERVATION_NOT_ACTIVE");
            });
  }
}
