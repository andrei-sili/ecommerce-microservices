package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.order.event.OrderPlacedPayload;
import com.ecommerce.order.event.OutboxService;
import com.ecommerce.order.model.OrderEntity;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.OutboxEvent;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OutboxEventRepository;
import com.ecommerce.order.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Golden-fixture pin for the {@code OrderPlaced} wire payload (contract AC-0.5 / B6).
 *
 * <p>Every input is fixed — a literal order id, literal amounts and an explicit {@code occurredAt}
 * instant instead of a wall clock — so the serialized bytes are a pure function of the serializer
 * configuration. That is the point: a field renamed, reordered, dropped, added, re-cased, or an
 * instant that stops being an ISO-8601 string, all move these bytes and nothing else does.
 *
 * <p>The payload is produced by {@link OutboxService}'s own dedicated {@code JsonMapper}, NOT by
 * the Spring {@code ObjectMapper}: event payloads are camelCase while REST bodies are snake_case
 * (see {@code rules/events.md}). That mapper is production code and is not shadowed by the test
 * {@code application.yml}, so this fixture stays falsifiable on a service where the yml shadow
 * blinds the REST-side casing pins.
 */
class OutboxGoldenPayloadTest extends AbstractIntegrationTest {

  private static final String GOLDEN = "golden/order-placed.json";

  private static final UUID ORDER_ID = UUID.fromString("3f1d9c4e-6b2a-4f18-9a77-2c5e8d0b1a63");
  private static final long USER_ID = 7L;
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-26T10:00:00Z");

  @Autowired private OutboxService outboxService;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void cleanDb() {
    outboxEventRepository.deleteAll();
    orderRepository.deleteAll();
  }

  @Test
  @Transactional
  void orderPlacedPayload_matchesGoldenFixtureByteForByte() throws Exception {
    recordFixedOrderPlaced();

    OutboxEvent row = outboxEventRepository.findAll().get(0);
    assertThat(row.getEventType()).isEqualTo("OrderPlaced");
    assertThat(row.getAggregateType()).isEqualTo("Order");
    assertThat(row.getAggregateId()).isEqualTo(ORDER_ID.toString());
    assertThat(row.getOccurredAt()).isEqualTo(OCCURRED_AT);

    assertThat(row.getPayload())
        .as(
            "OrderPlaced wire payload must match %s exactly — key names, key ORDER, number"
                + " formatting and the ISO-8601 instant are all contract",
            GOLDEN)
        .isEqualTo(golden());
  }

  /**
   * The same bytes, read as a tree, so a failure says WHICH field moved rather than only that the
   * strings differ. Also pins the camelCase/snake_case split explicitly: the event payload is
   * camelCase, and the SAME record through the REST {@code ObjectMapper} is snake_case. Asserting
   * both directions is what keeps either one falsifiable — if the two ever agree, one of the two
   * conventions has been lost.
   */
  @Test
  @Transactional
  void orderPlacedPayload_isCamelCase_andNotTheSnakeCaseRestConvention() throws Exception {
    recordFixedOrderPlaced();
    String payload = outboxEventRepository.findAll().get(0).getPayload();

    JsonNode root = objectMapper.readTree(payload);
    assertThat(root.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
    assertThat(root.get("userId").asLong()).isEqualTo(USER_ID);
    assertThat(root.get("occurredAt").asText()).isEqualTo("2026-06-26T10:00:00Z");
    assertThat(root.get("items").get(0).get("productId").asLong()).isEqualTo(42L);
    assertThat(root.get("items").get(0).get("unitPrice").asText()).isEqualTo("19.99");

    assertThat(payload)
        .as("event payload must not be produced by the snake_case REST convention")
        .doesNotContain(
            "\"order_id\"", "\"user_id\"", "\"product_id\"", "\"unit_price\"", "\"occurred_at\"");

    // The contrast that makes the line above falsifiable: the SAME record through the REST
    // ObjectMapper comes out snake_case. Two live, genuinely different conventions — so the
    // camelCase assertion above is a real choice by OutboxService, not a property both mappers
    // happen to share. (Re-serializing the parsed tree would prove nothing: a naming strategy
    // renames POJO properties, never Map keys, so that round trip is camelCase either way.)
    String sameRecordThroughRestMapper =
        objectMapper.writeValueAsString(
            new OrderPlacedPayload(
                ORDER_ID,
                USER_ID,
                List.of(new OrderPlacedPayload.Item(42L, 2, new BigDecimal("19.99"))),
                new BigDecimal("39.98"),
                "EUR",
                OCCURRED_AT));
    assertThat(sameRecordThroughRestMapper)
        .as("the REST mapper is SNAKE_CASE — if it is not, the casing split no longer exists")
        .contains(
            "\"order_id\"", "\"user_id\"", "\"product_id\"", "\"unit_price\"", "\"occurred_at\"");
    assertThat(sameRecordThroughRestMapper).isNotEqualTo(payload);
  }

  private void recordFixedOrderPlaced() {
    OrderEntity order =
        new OrderEntity(
            ORDER_ID,
            USER_ID,
            OrderStatus.PENDING,
            "EUR",
            new BigDecimal("39.98"),
            new BigDecimal("39.98"),
            "golden-fixture-key");
    order.addItem(
        new OrderItem(42L, "Black T-Shirt", new BigDecimal("19.99"), 2, new BigDecimal("39.98")));
    orderRepository.saveAndFlush(order);

    outboxService.recordOrderPlaced(order, OCCURRED_AT);
  }

  /**
   * The committed fixture. Trailing whitespace is stripped because the file ends with a newline
   * (POSIX text file) while the serialized payload does not — no other normalisation is applied, so
   * any difference in keys, order or values still fails.
   */
  private String golden() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(GOLDEN)) {
      assertThat(in).as("golden fixture %s must be on the test classpath", GOLDEN).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
    }
  }
}
