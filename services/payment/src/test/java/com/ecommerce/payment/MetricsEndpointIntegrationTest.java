package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import com.ecommerce.payment.support.TestJwt;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wave 5c observability: the Prometheus scrape endpoint must be reachable by an unauthenticated
 * scraper through the REAL security chain, while business endpoints stay locked. No filter chain is
 * mocked here — the app's own {@code SecurityFilterChain} decides every request. The exposure list
 * mirrors production {@code application.yml} (the shared test yml keeps actuator defaults);
 * {@code @AutoConfigureObservability} re-enables the metrics export that {@code @SpringBootTest}
 * disables by default, so the real Prometheus endpoint is created just like in production.
 */
@AutoConfigureObservability
@TestPropertySource(properties = "management.endpoints.web.exposure.include=health,info,prometheus")
class MetricsEndpointIntegrationTest extends AbstractIntegrationTest {

  private static final String PROMETHEUS_PATH = "/actuator/prometheus";
  private static final String USER_BEARER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  @Autowired private MockMvc mockMvc;

  // Mocked only to keep context loading identical to the business tests (no broker, no outbound
  // HTTP). This test exercises neither — the security chain is fully real.
  @MockBean private OutboxRelay outboxRelay;
  @MockBean private OrderClient orderClient;

  @Test
  void prometheusEndpoint_noAuthHeader_returns200_textPlain_withMetrics() throws Exception {
    String body =
        mockMvc
            .perform(get(PROMETHEUS_PATH))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("# HELP");
    assertThat(body).contains("jvm_");
  }

  @Test
  void prometheusPath_isNotUnderApiV1() {
    assertThat(PROMETHEUS_PATH).doesNotStartWith("/api/v1");
  }

  @Test
  void protectedPaymentEndpoint_noJwt_stillReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedPaymentEndpoint_invalidJwt_stillReturns401() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/payments/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void metricsPermit_doesNotLoosenOtherActuatorEndpoints() throws Exception {
    // A sibling actuator endpoint that was never permitted (env exposes config) must stay locked:
    // it is not exposed and falls under the terminal denyAll(), proving the permit is scoped to
    // exactly /actuator/prometheus, not "/actuator/**".
    mockMvc
        .perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, USER_BEARER))
        .andExpect(status().isForbidden());
  }
}
