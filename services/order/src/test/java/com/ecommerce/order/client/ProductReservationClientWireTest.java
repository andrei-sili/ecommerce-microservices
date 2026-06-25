package com.ecommerce.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ecommerce.order.config.ClientsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
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
 * Exercises REAL HTTP serialization through {@link ProductReservationClient} (no client mocking),
 * built from Boot's auto-configured {@code RestClient.Builder} — the same converters/ObjectMapper
 * production uses. Guards the snake_case wire contract in both directions: a mock hid this once and
 * the static {@code RestClient.builder()} (default camelCase mapper) caused a live 502 from
 * product-service.
 */
@SpringBootTest(classes = ProductReservationClientWireTest.Config.class)
@TestPropertySource(properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
class ProductReservationClientWireTest {

  @Configuration
  @ImportAutoConfiguration({
    JacksonAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    RestClientAutoConfiguration.class
  })
  static class Config {}

  // Boot's auto-configured builder: carries the snake_case ObjectMapper converter (same as prod).
  @Autowired private RestClient.Builder builder;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void reservationRequest_serializesSnakeCase_and_responseDeserializesPopulated() {
    RestClient.Builder clientBuilder = builder.clone().baseUrl("http://product.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
    RestClient restClient = clientBuilder.build();

    ClientsProperties props = new ClientsProperties();
    props.getProduct().setBaseUrl("http://product.test");
    props.getProduct().setInternalApiKey("test-internal-api-key");
    ProductReservationClient reservationClient =
        new ProductReservationClient(restClient, props, objectMapper);

    UUID orderId = UUID.fromString("9f1c2e7a-0000-0000-0000-000000000001");
    String snakeResponse =
        """
        {
          "order_id": "9f1c2e7a-0000-0000-0000-000000000001",
          "status": "RESERVED",
          "items": [
            { "product_id": 42, "name": "Black T-Shirt", "unit_price": 19.99,
              "currency": "EUR", "quantity": 2, "line_total": 39.98 }
          ],
          "currency": "EUR",
          "subtotal": 39.98
        }
        """;

    server
        .expect(requestTo("http://product.test/api/v1/inventory/reservations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Api-Key", "test-internal-api-key"))
        // Outgoing body MUST be snake_case per the contract: order_id / items[].product_id.
        .andExpect(jsonPath("$.order_id").value("9f1c2e7a-0000-0000-0000-000000000001"))
        .andExpect(jsonPath("$.items[0].product_id").value(42))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        // The camelCase keys must NOT be present on the wire.
        .andExpect(jsonPath("$.orderId").doesNotExist())
        .andExpect(jsonPath("$.items[0].productId").doesNotExist())
        .andRespond(withSuccess(snakeResponse, MediaType.APPLICATION_JSON));

    ReservationRequest request =
        new ReservationRequest(orderId, List.of(new ReservationRequest.Line(42L, 2)));
    ReservationResponse response = reservationClient.reserve(orderId, request);

    server.verify();
    // Response (snake_case) must populate the camelCase record fields — not null them out.
    assertThat(response.orderId()).isEqualTo(orderId);
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
  }
}
