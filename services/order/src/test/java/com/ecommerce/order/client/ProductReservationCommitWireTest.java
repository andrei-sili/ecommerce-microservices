package com.ecommerce.order.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.ecommerce.order.config.ClientsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wire test for the commit endpoint: verifies the correct URL, method, and header are sent, and
 * that a {@code 409 RESERVATION_NOT_ACTIVE} response is mapped to {@link
 * ProductReservationClient.ReservationNotActiveException}.
 */
@SpringBootTest(classes = ProductReservationCommitWireTest.Config.class)
@TestPropertySource(properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
class ProductReservationCommitWireTest {

  @Configuration
  @ImportAutoConfiguration({
    JacksonAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    RestClientAutoConfiguration.class
  })
  static class Config {}

  @Autowired private RestClient.Builder builder;
  @Autowired private ObjectMapper objectMapper;

  private ProductReservationClient buildClient(MockRestServiceServer server, RestClient.Builder b) {
    ClientsProperties props = new ClientsProperties();
    props.getProduct().setBaseUrl("http://product.test");
    props.getProduct().setInternalApiKey("test-internal-api-key");
    return new ProductReservationClient(b.build(), props, objectMapper);
  }

  @Test
  void commit_sendsPostWithInternalKey_andAccepts2xx() {
    RestClient.Builder clientBuilder = builder.clone().baseUrl("http://product.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
    ProductReservationClient client = buildClient(server, clientBuilder);

    UUID orderId = UUID.fromString("9f1c2e7a-0000-0000-0000-000000000042");
    server
        .expect(
            requestTo(
                "http://product.test/api/v1/inventory/reservations/9f1c2e7a-0000-0000-0000-000000000042/commit"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Api-Key", "test-internal-api-key"))
        .andRespond(withNoContent()); // 204

    client.commit(orderId); // must not throw
    server.verify();
  }

  @Test
  void commit_409_throwsReservationNotActiveException() {
    RestClient.Builder clientBuilder = builder.clone().baseUrl("http://product.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
    ProductReservationClient client = buildClient(server, clientBuilder);

    UUID orderId = UUID.fromString("9f1c2e7a-0000-0000-0000-000000000043");
    server
        .expect(
            requestTo(
                "http://product.test/api/v1/inventory/reservations/9f1c2e7a-0000-0000-0000-000000000043/commit"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CONFLICT));

    assertThatThrownBy(() -> client.commit(orderId))
        .isInstanceOf(ProductReservationClient.ReservationNotActiveException.class);
    server.verify();
  }
}
