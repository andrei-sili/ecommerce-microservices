package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartView;
import com.ecommerce.order.client.ProductReservationClient;
import com.ecommerce.order.client.ReservationResponse;
import com.ecommerce.order.exception.InsufficientStockException;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.ContractShape;
import com.ecommerce.order.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-context guard for framework/dispatcher error mapping: unmapped path, wrong method, bad media
 * type, unreadable body and type mismatch must render the standard envelope with the correct 4xx
 * status — never a 500 — while security (401), validation (400) and the order-specific domain
 * handlers (insufficient stock, missing Idempotency-Key) stay intact. Runs against the real
 * security chain + dispatcher + Postgres, authenticating with a valid JWT minted exactly like the
 * existing order integration tests.
 */
class FrameworkErrorMappingIntegrationTest extends AbstractIntegrationTest {

  private static final long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;

  @MockitoBean private CartClient cartClient;
  @MockitoBean private ProductReservationClient reservationClient;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
  }

  // 1. Unmapped sub-path with a valid token -> 404 RESOURCE_NOT_FOUND, full JSON envelope, no leak.
  @Test
  void unmappedSubPath_returns404_withEnvelope_jsonContentType_noLeak() throws Exception {
    String path = "/api/v1/orders/" + UUID.randomUUID() + "/items";

    MvcResult result =
        mockMvc
            .perform(get(path).header("Authorization", USER))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
            .andExpect(jsonPath("$.message", notNullValue()))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.path", is(path)))
            // Error envelope is application/json, NOT application/problem+json.
            .andExpect(header().string("Content-Type", containsString("application/json")))
            .andReturn();

    String contentType = result.getResponse().getContentType();
    assertFalse(
        contentType != null && contentType.contains("problem"),
        "framework error must not use application/problem+json, was: " + contentType);
    assertNoLeak(result);
  }

  // 2. A second, collection-level unmapped path (×2) -> 404 RESOURCE_NOT_FOUND; message must NOT
  // echo the path or leak the "No static resource ..." text.
  @Test
  void unmappedCollectionPath_returns404_withEnvelope_noPathLeak() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/order-archive").header("Authorization", USER))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
            .andExpect(jsonPath("$.message", is("Resource not found")))
            .andExpect(jsonPath("$.path", is("/api/v1/order-archive")))
            .andReturn();

    assertNoLeak(result);
  }

  // 3. Wrong HTTP method on a mapped route -> 405 + Allow header listing GET and PATCH.
  @Test
  void wrongMethod_returns405_withAllowHeader() throws Exception {
    String path = "/api/v1/orders/" + UUID.randomUUID();

    MvcResult result =
        mockMvc
            .perform(delete(path).header("Authorization", USER))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.error", is("METHOD_NOT_ALLOWED")))
            .andExpect(jsonPath("$.path", is(path)))
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(header().string("Allow", containsString("PATCH")))
            .andReturn();

    assertNoLeak(result);
  }

  // 4. Unsupported Content-Type on a body route (PATCH carries @RequestBody) -> 415 + Accept
  // header.
  @Test
  void unsupportedMediaType_returns415_withAcceptHeader() throws Exception {
    String path = "/api/v1/orders/" + UUID.randomUUID();

    MvcResult result =
        mockMvc
            .perform(
                patch(path)
                    .header("Authorization", USER)
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("just some plain text"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error", is("UNSUPPORTED_MEDIA_TYPE")))
            .andExpect(jsonPath("$.path", is(path)))
            .andExpect(header().exists("Accept"))
            .andReturn();

    assertNoLeak(result);
  }

  // 5. Malformed / unreadable JSON body (regression guard) -> 400 MALFORMED_REQUEST.
  @Test
  void malformedJson_returns400_malformedRequest() throws Exception {
    String path = "/api/v1/orders/" + UUID.randomUUID();

    MvcResult result =
        mockMvc
            .perform(
                patch(path)
                    .header("Authorization", USER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("MALFORMED_REQUEST")))
            .andExpect(jsonPath("$.message", is("Request body or parameter is malformed")))
            .andExpect(jsonPath("$.path", is(path)))
            .andReturn();

    assertNoLeak(result);
  }

  // 6. Bean-Validation failure (regression guard) -> 400 VALIDATION_ERROR naming the field.
  @Test
  void invalidBody_returns400_validationError_withField() throws Exception {
    String path = "/api/v1/orders/" + UUID.randomUUID();

    MvcResult result =
        mockMvc
            .perform(
                patch(path)
                    .header("Authorization", USER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
            .andExpect(jsonPath("$.message", containsString("status")))
            .andExpect(jsonPath("$.path", is(path)))
            .andReturn();

    assertNoLeak(result);
  }

  // 7. Path-variable type mismatch (proves the type-mismatch split handler) -> 400
  // MALFORMED_REQUEST.
  @Test
  void typeMismatchPathVar_returns400_malformedRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/orders/not-a-uuid").header("Authorization", USER))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("MALFORMED_REQUEST")))
            .andExpect(jsonPath("$.path", is("/api/v1/orders/not-a-uuid")))
            .andReturn();

    assertNoLeak(result);
  }

  // 8. Security regression guard: no Authorization header -> 401 UNAUTHORIZED (entry point).
  @Test
  void noToken_returns401_unauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", is("/api/v1/orders")));
  }

  // 8b. The 401 envelope carries EXACTLY the four contract keys. Order's ErrorResponse is a
  // 5-component record whose productId is omitted only because of @JsonInclude(NON_NULL): if
  // inclusion handling ever changes, "product_id": null appears here. Asserted as an exact key SET
  // (never a subset) so that regression is a failure, not a silent widening.
  @Test
  void noToken_401_carriesExactlyTheFourContractKeys_withIso8601Timestamp() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/orders"))
            .andExpect(status().isUnauthorized())
            // A9: exact, so a charset appearing on the entry point's getWriter() path is caught.
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
            .andExpect(jsonPath("$.message", is("Authentication required")))
            .andExpect(jsonPath("$.path", is("/api/v1/orders")))
            .andReturn();

    // A7: the auth rows bypass the @RestControllerAdvice entirely (entry point / denied handler),
    // so the problem+json guard has to be asserted here too, not only on the dispatcher rows.
    String contentType = result.getResponse().getContentType();
    assertFalse(
        contentType != null && contentType.contains("problem"),
        "401 must not use application/problem+json, was: " + contentType);

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(ContractShape.keysOf(body))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    ContractShape.assertIso8601Utc(body, "timestamp");
  }

  /**
   * {@code path} is echoed straight from {@code request.getRequestURI()}, which is attacker
   * controlled and un-decoded. It must round-trip byte-identically: no decoding (which would let a
   * {@code %2e%2e} segment read as {@code ..} downstream), no re-encoding, no charset mangling.
   * Servlet 6.1 / Tomcat 11 change URI handling, so this is pinned rather than assumed.
   */
  @Test
  void nonAsciiPercentEncodedPath_roundTripsByteIdentically_inTheEnvelope() throws Exception {
    String rawPath = "/api/v1/orders/caf%C3%A9-%E2%82%AC";

    MvcResult result =
        mockMvc
            .perform(get(URI.create(rawPath)).header("Authorization", USER))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("MALFORMED_REQUEST")))
            .andExpect(jsonPath("$.path", is(rawPath)))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(ContractShape.keysOf(body))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path");
    assertThat(body.get("path").asText())
        .as("the echoed path must be the raw request URI, neither decoded nor re-encoded")
        .isEqualTo(rawPath)
        .doesNotContain("café")
        .doesNotContain("€");
    assertNoLeak(result);
  }

  // 11b. The 409 counterpart of the pin above: the SAME record renders FIVE keys when productId is
  // present. The pair is the discriminating assertion — one of them breaks whichever way NON_NULL
  // handling drifts.
  @Test
  void insufficientStock_409_carriesExactlyFiveKeys_includingProductId() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenThrow(new InsufficientStockException(42L, "Insufficient stock for Black T-Shirt"));

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-stock-keyset"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("INSUFFICIENT_STOCK")))
            .andExpect(jsonPath("$.message", is("Insufficient stock for Black T-Shirt")))
            .andExpect(jsonPath("$.path", is("/api/v1/orders")))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(ContractShape.keysOf(body))
        .containsExactlyInAnyOrder("error", "message", "timestamp", "path", "product_id");
    assertThat(body.get("product_id").asLong()).isEqualTo(42L);
    ContractShape.assertIso8601Utc(body, "timestamp");
  }

  // 9. Happy-path control: a mapped list route with a valid token returns 200 + pagination
  // envelope.
  @Test
  void mappedListRoute_withValidToken_returns200() throws Exception {
    mockMvc
        .perform(get("/api/v1/orders").header("Authorization", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_elements").value(0))
        .andExpect(jsonPath("$.content").isArray());
  }

  // 10. Happy-path control: a mapped item route with a valid token returns 200 + the resource.
  @Test
  void mappedItemRoute_withValidToken_returns200() throws Exception {
    UUID orderId = placeOrder("key-happy-get");

    mockMvc
        .perform(get("/api/v1/orders/" + orderId).header("Authorization", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orderId.toString()));
  }

  // 11. Domain regression: insufficient stock still renders its product_id envelope (409).
  @Test
  void insufficientStock_returns409_withProductIdEnvelope() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenThrow(new InsufficientStockException(42L, "Insufficient stock for Black T-Shirt"));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-stock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error", is("INSUFFICIENT_STOCK")))
        .andExpect(jsonPath("$.product_id").value(42));
  }

  // 12. Domain regression: a MISSING Idempotency-Key still returns IDEMPOTENCY_KEY_REQUIRED (400).
  @Test
  void missingIdempotencyKey_returns400_idempotencyKeyRequired() throws Exception {
    mockMvc
        .perform(post("/api/v1/orders").header("Authorization", USER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", is("IDEMPOTENCY_KEY_REQUIRED")))
        .andExpect(jsonPath("$.path", is("/api/v1/orders")));
  }

  private UUID placeOrder(String key) throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenAnswer(inv -> reservation(inv.getArgument(0)));
    String body =
        mockMvc
            .perform(
                post("/api/v1/orders").header("Authorization", USER).header("Idempotency-Key", key))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private CartView nonEmptyCart() {
    return new CartView(
        USER_ID, "EUR", List.of(new CartView.CartItem(42L, 2)), new BigDecimal("39.98"));
  }

  private ReservationResponse reservation(UUID orderId) {
    return new ReservationResponse(
        orderId,
        "RESERVED",
        List.of(
            new ReservationResponse.Item(
                42L, "Black T-Shirt", new BigDecimal("19.99"), "EUR", 2, new BigDecimal("39.98"))),
        "EUR",
        new BigDecimal("39.98"));
  }

  /** No error body may leak the static-resource message, a stack trace or other internals. */
  private void assertNoLeak(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    for (String forbidden :
        new String[] {
          "No static resource",
          "nested exception",
          "Exception",
          "\tat ",
          "java.lang.",
          "org.springframework."
        }) {
      assertFalse(
          body.contains(forbidden), "error body leaked internals (\"" + forbidden + "\"): " + body);
    }
  }
}
