package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderView;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * Wire test: verifies the auto-configured RestClient.Builder (Boot's bean, SNAKE_CASE ObjectMapper)
 * is used by OrderClient. @RestClientTest wires Boot's Jackson auto-config (including
 * property-naming-strategy: SNAKE_CASE from test application.yml) and a MockRestServiceServer bound
 * to the RestClient.Builder. If someone accidentally replaced the Boot builder with the static
 * RestClient.builder(), snake_case fields like order_id → orderId would break (the
 * restclient-snakecase-gotcha produced a 400 in Wave 2 for exactly this reason).
 */
@RestClientTest(OrderClient.class)
@TestPropertySource(
    properties = {
      "clients.order.base-url=http://order-service",
      "clients.connect-timeout-ms=2000",
      "clients.read-timeout-ms=5000",
      "spring.jackson.property-naming-strategy=SNAKE_CASE"
    })
class OrderClientWireTest {

  @Autowired private OrderClient orderClient;

  @Autowired private MockRestServiceServer server;

  @Test
  void getOrder_deserializesSnakeCaseResponse_correctly() {
    UUID orderId = UUID.randomUUID();

    server
        .expect(requestTo(Matchers.containsString("/api/v1/orders/" + orderId)))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer testtoken"))
        .andRespond(
            withSuccess(
                """
                {"order_id":"%s","user_id":7,"status":"PENDING","currency":"EUR",\
                "total":39.98,"subtotal":39.98}
                """
                    .formatted(orderId),
                MediaType.APPLICATION_JSON));

    OrderView view = orderClient.getOrder(orderId, "Bearer testtoken");

    // These assertions prove snake_case deserialization works via Boot's SNAKE_CASE ObjectMapper:
    // "user_id" → userId, "order_id" → orderId. Without the Boot-configured builder these fail.
    assertThat(view).isNotNull();
    assertThat(view.userId()).isEqualTo(7L);
    assertThat(view.orderId()).isEqualTo(orderId);
    assertThat(view.total()).isEqualByComparingTo("39.98");
    assertThat(view.currency()).isEqualTo("EUR");
    assertThat(view.status()).isEqualTo("PENDING");

    server.verify();
  }
}
