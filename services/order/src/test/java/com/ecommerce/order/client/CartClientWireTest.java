package com.ecommerce.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Exercises REAL HTTP deserialization through {@link CartClient}, built from Boot's auto-configured
 * {@code RestClient.Builder} (same snake_case converters as production). Guards that the Cart
 * Service's snake_case response maps into {@link CartView} with populated fields — a default
 * camelCase mapper would silently null product_id / unit_price / line_total.
 */
@SpringBootTest(classes = CartClientWireTest.Config.class)
@TestPropertySource(properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
class CartClientWireTest {

  @Configuration
  @ImportAutoConfiguration({
    JacksonAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    RestClientAutoConfiguration.class
  })
  static class Config {}

  @Autowired private RestClient.Builder builder;

  @Test
  void cartResponse_snakeCase_mapsIntoCartView_populated() {
    RestClient.Builder clientBuilder = builder.clone().baseUrl("http://cart.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
    CartClient cartClient = new CartClient(clientBuilder.build());

    String snakeCart =
        """
        {
          "user_id": 7,
          "currency": "EUR",
          "items": [ { "product_id": 42, "product_name": "Black T-Shirt", "unit_price": 19.99,
            "quantity": 2, "line_total": 39.98, "snapshot_at": "2026-06-25T10:30:00Z" } ],
          "subtotal": 39.98,
          "total_items": 2,
          "updated_at": "2026-06-25T10:30:00Z"
        }
        """;

    server
        .expect(requestTo("http://cart.test/api/v1/cart"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer user-token"))
        .andRespond(withSuccess(snakeCart, MediaType.APPLICATION_JSON));

    CartView cart = cartClient.getCart("user-token");

    server.verify();
    assertThat(cart).isNotNull();
    assertThat(cart.userId()).isEqualTo(7L);
    assertThat(cart.currency()).isEqualTo("EUR");
    assertThat(cart.subtotal()).isEqualByComparingTo("39.98");
    assertThat(cart.isEmpty()).isFalse();
    assertThat(cart.items()).hasSize(1);
    assertThat(cart.items().get(0).productId()).isEqualTo(42L);
    assertThat(cart.items().get(0).quantity()).isEqualTo(2);
  }
}
