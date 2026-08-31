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
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wave 5c observability: the Prometheus scrape endpoint must be reachable by an unauthenticated
 * scraper through the REAL security chain, while business endpoints stay locked. No filter chain is
 * mocked here — the app's own {@code SecurityFilterChain} decides every request.
 * {@code @AutoConfigureMetrics} re-enables the metrics export that {@code @SpringBootTest} disables
 * by default, so the real Prometheus endpoint is created just like in production.
 *
 * <p><b>The exposure list is NOT re-declared here.</b> It used to be, in a
 * {@code @TestPropertySource} mirroring production because the shared test yml kept actuator
 * defaults — which meant this class scraped an endpoint it had exposed itself, and dropping {@code
 * prometheus} from the shipped {@code exposure.include} would have stayed green (contract F5). It
 * now comes from the shipped {@code application.yml} through the {@code test} profile overlay.
 */
@AutoConfigureMetrics
class MetricsEndpointIntegrationTest extends AbstractIntegrationTest {

  private static final String PROMETHEUS_PATH = "/actuator/prometheus";

  /**
   * Exact scrape content type captured on 3.5.16, charset included (A9). {@code
   * contentTypeCompatibleWith(TEXT_PLAIN)} ignores both the {@code version} parameter and the
   * charset, so it cannot catch a Micrometer format change that would silently break every
   * Prometheus scrape.
   */
  private static final String PROMETHEUS_TEXT = "text/plain;version=0.0.4;charset=utf-8";

  private static final String USER_BEARER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  @Autowired private MockMvc mockMvc;

  // Mocked only to keep context loading identical to the business tests (no broker, no outbound
  // HTTP). This test exercises neither — the security chain is fully real.
  @MockitoBean private OutboxRelay outboxRelay;
  @MockitoBean private OrderClient orderClient;

  @Test
  void prometheusEndpoint_noAuthHeader_returns200_textPlain_withMetrics() throws Exception {
    String body =
        mockMvc
            .perform(get(PROMETHEUS_PATH))
            .andExpect(status().isOk())
            .andExpect(content().contentType(PROMETHEUS_TEXT))
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
