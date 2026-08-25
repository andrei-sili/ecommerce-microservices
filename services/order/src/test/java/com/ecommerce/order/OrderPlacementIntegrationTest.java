package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.CartView;
import com.ecommerce.order.client.ProductReservationClient;
import com.ecommerce.order.client.ReservationResponse;
import com.ecommerce.order.exception.InsufficientStockException;
import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.service.OrderPersistenceService;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.ecommerce.order.support.ContractShape;
import com.ecommerce.order.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

class OrderPlacementIntegrationTest extends AbstractIntegrationTest {

  private static final long USER_ID = 7L;
  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));
  private static final String OTHER_USER = TestJwt.bearer(TestJwt.token("99", List.of("USER")));
  private static final String ADMIN = TestJwt.bearer(TestJwt.token("1", List.of("ADMIN")));

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;

  @MockitoBean private CartClient cartClient;
  @MockitoBean private ProductReservationClient reservationClient;
  @MockitoSpyBean private OrderPersistenceService persistenceService;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
  }

  @AfterEach
  void resetMocks() {
    org.mockito.Mockito.reset(persistenceService);
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

  // ---- Happy path ----

  @Test
  void placeOrder_happyPath_persistsOrderItemsAndOutbox_clearsCart() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenAnswer(inv -> reservation(inv.getArgument(0)));

    String body =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-happy"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.subtotal").value(39.98))
            .andExpect(jsonPath("$.total").value(39.98))
            .andExpect(jsonPath("$.items[0].product_id").value(42))
            .andExpect(jsonPath("$.items[0].unit_price").value(19.99))
            .andExpect(jsonPath("$.items[0].line_total").value(39.98))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("\"user_id\":7");

    List<OrderEntity> orders = orderRepository.findAll();
    assertThat(orders).hasSize(1);
    OrderEntity order = orderRepository.findWithItemsById(orders.get(0).getId()).orElseThrow();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getUserId()).isEqualTo(USER_ID);
    assertThat(order.getItems()).hasSize(1);
    assertThat(order.getItems().get(0).getUnitPrice()).isEqualByComparingTo("19.99");

    List<OutboxEvent> events = outboxEventRepository.findAll();
    assertThat(events).hasSize(1);
    OutboxEvent event = events.get(0);
    assertThat(event.getEventType()).isEqualTo("OrderPlaced");
    assertThat(event.getAggregateType()).isEqualTo("Order");
    assertThat(event.getAggregateId()).isEqualTo(order.getId().toString());
    // Payload is camelCase per the OrderPlaced contract (Postgres JSONB normalizes whitespace, so
    // assert on parsed values rather than the raw string).
    String payload = event.getPayload();
    assertThat((Object) com.jayway.jsonpath.JsonPath.read(payload, "$.orderId"))
        .isEqualTo(order.getId().toString());
    assertThat((Integer) com.jayway.jsonpath.JsonPath.read(payload, "$.userId")).isEqualTo(7);
    assertThat((Integer) com.jayway.jsonpath.JsonPath.read(payload, "$.items[0].productId"))
        .isEqualTo(42);
    assertThat((Integer) com.jayway.jsonpath.JsonPath.read(payload, "$.items[0].quantity"))
        .isEqualTo(2);
    assertThat(((Number) com.jayway.jsonpath.JsonPath.read(payload, "$.items[0].unitPrice")))
        .isEqualTo(19.99);
    assertThat(((Number) com.jayway.jsonpath.JsonPath.read(payload, "$.total"))).isEqualTo(39.98);
    assertThat((Object) com.jayway.jsonpath.JsonPath.read(payload, "$.currency")).isEqualTo("EUR");
    assertThat((Object) com.jayway.jsonpath.JsonPath.read(payload, "$.occurredAt")).isNotNull();
    assertThat(event.getPublishedAt()).isNull();

    verify(cartClient).clearCart(any());
  }

  @Test
  void placeOrder_cartClearFails_isNotFatal() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenAnswer(inv -> reservation(inv.getArgument(0)));
    doThrow(new RuntimeException("cart down")).when(cartClient).clearCart(any());

    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-clearfail"))
        .andExpect(status().isCreated());

    assertThat(orderRepository.findAll()).hasSize(1);
  }

  // ---- Wire format pins ----

  /**
   * {@code created_at} / {@code updated_at} are ISO-8601 UTC Strings on every representation. The
   * suite has plenty of assertions that these fields exist; none pinned their FORM, so a serializer
   * default flipping to epoch numbers would stay green while every consumer breaks.
   */
  @Test
  void orderDates_areIso8601UtcStrings_onCreateGetAndList() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenAnswer(inv -> reservation(inv.getArgument(0)));

    String created =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-dates"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode createdBody = objectMapper.readTree(created);
    ContractShape.assertIso8601Utc(createdBody, "created_at");
    ContractShape.assertIso8601Utc(createdBody, "updated_at");

    String orderId = createdBody.get("id").asText();
    JsonNode fetched =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/v1/orders/" + orderId).header("Authorization", USER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    ContractShape.assertIso8601Utc(fetched, "created_at");
    ContractShape.assertIso8601Utc(fetched, "updated_at");

    JsonNode listed =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/v1/orders").header("Authorization", USER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    ContractShape.assertIso8601Utc(listed.get("content").get(0), "created_at");
    ContractShape.assertIso8601Utc(listed.get("content").get(0), "updated_at");
  }

  /**
   * The OrderPlaced payload is the cross-service wire contract (Notification consumes it), and it
   * is camelCase while every REST body is snake_case. Pins the EXACT key set at both levels: an
   * extra or renamed key is a breaking change for a consumer this service cannot see.
   */
  @Test
  void orderPlacedPayload_hasExactCamelCaseKeySet_andIso8601OccurredAt() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenAnswer(inv -> reservation(inv.getArgument(0)));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-payload-shape"))
        .andExpect(status().isCreated());

    List<OutboxEvent> events = outboxEventRepository.findAll();
    assertThat(events).hasSize(1);
    String payload = events.get(0).getPayload();
    JsonNode root = objectMapper.readTree(payload);

    assertThat(ContractShape.keysOf(root))
        .containsExactlyInAnyOrder("orderId", "userId", "items", "total", "currency", "occurredAt");
    assertThat(root.get("items")).hasSize(1);
    assertThat(ContractShape.keysOf(root.get("items").get(0)))
        .containsExactlyInAnyOrder("productId", "quantity", "unitPrice");
    ContractShape.assertIso8601Utc(root, "occurredAt");

    // Serialized by a dedicated mapper, so the REST snake_case strategy must never leak in.
    assertThat(payload)
        .doesNotContain("\"order_id\"")
        .doesNotContain("\"user_id\"")
        .doesNotContain("\"product_id\"")
        .doesNotContain("\"unit_price\"")
        .doesNotContain("\"occurred_at\"");
  }

  // ---- Empty cart ----

  @Test
  void placeOrder_emptyCart_returns422_nothingPersisted() throws Exception {
    when(cartClient.getCart(any()))
        .thenReturn(new CartView(USER_ID, null, List.of(), BigDecimal.ZERO));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-empty"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("EMPTY_CART"));

    assertThat(orderRepository.findAll()).isEmpty();
    verify(reservationClient, never()).reserve(any(), any());
  }

  // ---- Insufficient stock ----

  @Test
  void placeOrder_insufficientStock_returns409_nothingPersisted() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenThrow(new InsufficientStockException(42L, "Insufficient stock for Black T-Shirt"));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-stock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
        .andExpect(jsonPath("$.product_id").value(42));

    assertThat(orderRepository.findAll()).isEmpty();
    assertThat(outboxEventRepository.findAll()).isEmpty();
  }

  // ---- Idempotency ----

  @Test
  void placeOrder_replayedKey_returnsSameOrder_reservesOnce() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenAnswer(inv -> reservation(inv.getArgument(0)));

    String first =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-replay"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.id");

    String second =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .header("Authorization", USER)
                    .header("Idempotency-Key", "key-replay"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondId = com.jayway.jsonpath.JsonPath.read(second, "$.id");

    assertThat(secondId).isEqualTo(firstId);
    assertThat(orderRepository.findAll()).hasSize(1);
    // Reservation happened exactly once (the replay short-circuits before reserving).
    verify(reservationClient, times(1)).reserve(any(), any());
  }

  // ---- Ownership isolation ----

  @Test
  void getOrder_otherUser_returns404_doesNotLeakExistence() throws Exception {
    UUID orderId = placeOrderFor();

    mockMvc
        .perform(get("/api/v1/orders/" + orderId).header("Authorization", OTHER_USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
  }

  @Test
  void getOrder_owner_returns200() throws Exception {
    UUID orderId = placeOrderFor();

    mockMvc
        .perform(get("/api/v1/orders/" + orderId).header("Authorization", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orderId.toString()));
  }

  @Test
  void getOrder_admin_canReadAnyOrder() throws Exception {
    UUID orderId = placeOrderFor();

    mockMvc
        .perform(get("/api/v1/orders/" + orderId).header("Authorization", ADMIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orderId.toString()));
  }

  @Test
  void listOrders_returnsOnlyCallersOrders_withItems() throws Exception {
    placeOrderFor("key-list-1");
    placeOrderFor("key-list-2");

    mockMvc
        .perform(get("/api/v1/orders").header("Authorization", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_elements").value(2))
        .andExpect(jsonPath("$.content.length()").value(2))
        // Items are batch-loaded for the page; the list response keeps the full per-order shape.
        .andExpect(jsonPath("$.content[0].items.length()").value(1))
        .andExpect(jsonPath("$.content[0].items[0].product_id").value(42))
        .andExpect(jsonPath("$.content[1].items[0].product_id").value(42));

    mockMvc
        .perform(get("/api/v1/orders").header("Authorization", OTHER_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_elements").value(0));
  }

  @Test
  void listOrders_paginatesInSql_newestFirst() throws Exception {
    placeOrderFor("key-page-1");
    Thread.sleep(10); // ensure distinct created_at so newest-first ordering is deterministic
    placeOrderFor("key-page-2");
    Thread.sleep(10);
    UUID newest = placeOrderFor("key-page-3");

    // First page of size 2 must hold the two newest orders, newest first, with items populated.
    mockMvc
        .perform(get("/api/v1/orders?page=0&size=2").header("Authorization", USER))
        .andExpect(status().isOk())
        // D7: the pagination envelope is EXACTLY five top-level keys. Individual jsonPaths cannot
        // see a sixth key appear, and the envelope is what every list client parses.
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.total_elements").value(3))
        .andExpect(jsonPath("$.total_pages").value(2))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].id").value(newest.toString()))
        .andExpect(jsonPath("$.content[0].items[0].product_id").value(42));

    // Second page holds the single oldest order.
    mockMvc
        .perform(get("/api/v1/orders?page=1&size=2").header("Authorization", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.content.length()").value(1));
  }

  // ---- Cancel ----

  @Test
  void cancelOrder_pending_cancels_andReleasesReservation() throws Exception {
    UUID orderId = placeOrderFor();

    mockMvc
        .perform(
            patch("/api/v1/orders/" + orderId)
                .header("Authorization", USER)
                .contentType("application/json")
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.CANCELLED);
    verify(reservationClient).release(orderId);
  }

  @Test
  void cancelOrder_alreadyCancelled_isIdempotent_returns200_releasesAtMostOnce() throws Exception {
    UUID orderId = placeOrderFor();
    mockMvc
        .perform(
            patch("/api/v1/orders/" + orderId)
                .header("Authorization", USER)
                .contentType("application/json")
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    // Retry of cancel on an already-CANCELLED order: 200 with the order, no second release.
    mockMvc
        .perform(
            patch("/api/v1/orders/" + orderId)
                .header("Authorization", USER)
                .contentType("application/json")
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.CANCELLED);
    // Idempotent: the reservation release happened exactly once across both PATCH calls.
    verify(reservationClient, times(1)).release(orderId);
  }

  @Test
  void cancelOrder_otherUser_returns404() throws Exception {
    UUID orderId = placeOrderFor();
    mockMvc
        .perform(
            patch("/api/v1/orders/" + orderId)
                .header("Authorization", OTHER_USER)
                .contentType("application/json")
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isNotFound());
  }

  // ---- Compensation ----

  @Test
  void placeOrder_localTxFails_compensatesByReleasingReservation() throws Exception {
    when(cartClient.getCart(any())).thenReturn(nonEmptyCart());
    when(reservationClient.reserve(any(), any()))
        .thenAnswer(inv -> reservation(inv.getArgument(0)));
    doThrow(new RuntimeException("db write failed"))
        .when(persistenceService)
        .persistPlacedOrder(any(), any(), any(), any());

    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Authorization", USER)
                .header("Idempotency-Key", "key-compensate"))
        .andExpect(status().isInternalServerError());

    assertThat(orderRepository.findAll()).isEmpty();
    // The reservation that was taken in step (c) must be released on compensation.
    ArgumentCaptor<UUID> released = ArgumentCaptor.forClass(UUID.class);
    verify(reservationClient).release(released.capture());

    ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
    verify(reservationClient).reserve(reserved.capture(), any());
    assertThat(released.getValue()).isEqualTo(reserved.getValue());
  }

  // ---- Helpers ----

  private UUID placeOrderFor() throws Exception {
    return placeOrderFor("key-" + UUID.randomUUID());
  }

  private UUID placeOrderFor(String key) throws Exception {
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
    String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
    return UUID.fromString(id);
  }
}
