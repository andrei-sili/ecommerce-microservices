package com.ecommerce.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.client.ProductClient;
import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.ecommerce.cart.support.StubProductClient;
import com.ecommerce.cart.support.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Wire-shape pin for the cart body, captured on 3.5.16 as {@code baselines/boot4/shapes/cart.shape}
 * and asserted here as an EXACT key set per level plus an exact JSON node type per key.
 *
 * <p>Two properties of the current serializer are load-bearing and invisible to a subset assertion.
 * (1) {@code currency} is present only when the cart holds items — it survives on {@code
 * spring.jackson.default-property-inclusion: non_null}, so a change in null handling makes an empty
 * cart grow a {@code currency: null} key; an exact key SET is the only assertion that turns red for
 * a GAINED key. (2) {@code updated_at} / {@code snapshot_at} are ISO-8601 UTC strings only because
 * {@code WRITE_DATES_AS_TIMESTAMPS} is off; if it flips they become numbers and every consumer
 * parsing them breaks, while a {@code notNullValue()} assertion stays green.
 */
@Import(CartBodyShapeIntegrationTest.StubConfig.class)
class CartBodyShapeIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void dualAllowlist(DynamicPropertyRegistry registry) {
    // This suite presents legacy HS256 tokens as "a valid token"; production defaults to RS256-only
    // (Slice 5e phase 3), so pin the dual (rollback) allowlist independently of the shipped
    // default.
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
  }

  @TestConfiguration
  static class StubConfig {
    @Bean
    @Primary
    StubProductClient stubProductClient() {
      return new StubProductClient();
    }
  }

  /** ISO-8601 UTC instant, optional fractional seconds — the fleet-wide date wire format (B6). */
  private static final Pattern ISO_8601_UTC =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

  private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");

  private static final Set<String> CART_KEYS =
      Set.of("user_id", "currency", "items", "subtotal", "total_items", "updated_at");

  /** An empty cart carries no currency: the key is OMITTED, not rendered as null. */
  private static final Set<String> EMPTY_CART_KEYS =
      Set.of("user_id", "items", "subtotal", "total_items", "updated_at");

  private static final Set<String> ITEM_KEYS =
      Set.of("product_id", "product_name", "unit_price", "quantity", "line_total", "snapshot_at");

  private static final String USER_7 = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ProductClient productClient;
  @Autowired private CartRepository cartRepository;

  @BeforeEach
  void setUp() {
    cartRepository.deleteAll();
    StubProductClient stub = (StubProductClient) productClient;
    stub.reset();
    stub.register(42L, "Black T-Shirt", "19.99", "EUR", true);
  }

  @Test
  void cartWithItems_hasExactlyThePinnedSnakeCaseKeys_atEveryLevel() throws Exception {
    JsonNode body = bodyOf(addTwoShirts());

    assertEquals(CART_KEYS, keysOf(body), "cart body key set drifted from the 3.5.16 capture");
    assertEquals(1, body.get("items").size());
    assertEquals(
        ITEM_KEYS, keysOf(body.get("items").get(0)), "cart item key set drifted from the capture");
    assertAllKeysSnakeCase(body);
  }

  @Test
  void cartWithItems_rendersEachKeyWithThePinnedJsonType_andValue() throws Exception {
    JsonNode body = bodyOf(addTwoShirts());
    JsonNode item = body.get("items").get(0);

    assertTrue(body.get("user_id").isIntegralNumber(), "user_id must stay a JSON integer");
    assertEquals(7, body.get("user_id").asLong());
    assertTrue(body.get("currency").isTextual(), "currency must stay a JSON string");
    assertEquals("EUR", body.get("currency").asText());
    assertTrue(body.get("items").isArray(), "items must stay a JSON array");
    assertTrue(body.get("subtotal").isFloatingPointNumber(), "subtotal must stay a JSON number");
    assertEquals("39.98", body.get("subtotal").asText(), "subtotal must keep 2-decimal scale");
    assertTrue(body.get("total_items").isIntegralNumber(), "total_items must stay a JSON integer");
    assertEquals(2, body.get("total_items").asInt());
    assertTrue(body.get("updated_at").isTextual(), "updated_at must stay a JSON string");

    assertTrue(item.get("product_id").isIntegralNumber(), "product_id must stay a JSON integer");
    assertEquals(42, item.get("product_id").asLong());
    assertTrue(item.get("product_name").isTextual(), "product_name must stay a JSON string");
    assertEquals("Black T-Shirt", item.get("product_name").asText());
    assertTrue(
        item.get("unit_price").isFloatingPointNumber(), "unit_price must stay a JSON number");
    assertEquals("19.99", item.get("unit_price").asText(), "unit_price must keep 2-decimal scale");
    assertTrue(item.get("quantity").isIntegralNumber(), "quantity must stay a JSON integer");
    assertEquals(2, item.get("quantity").asInt());
    assertTrue(
        item.get("line_total").isFloatingPointNumber(), "line_total must stay a JSON number");
    assertEquals("39.98", item.get("line_total").asText(), "line_total must keep 2-decimal scale");
    assertTrue(item.get("snapshot_at").isTextual(), "snapshot_at must stay a JSON string");
  }

  @Test
  void cartDateFields_areIso8601UtcStrings_notEpochNumbers() throws Exception {
    JsonNode body = bodyOf(addTwoShirts());
    String updatedAt = body.get("updated_at").asText();
    String snapshotAt = body.get("items").get(0).get("snapshot_at").asText();

    assertTrue(
        ISO_8601_UTC.matcher(updatedAt).matches(), "updated_at is not ISO-8601 UTC: " + updatedAt);
    assertTrue(
        ISO_8601_UTC.matcher(snapshotAt).matches(),
        "snapshot_at is not ISO-8601 UTC: " + snapshotAt);
    // Round-trip proves the string is a real instant, not merely regex-shaped.
    Instant.parse(updatedAt);
    Instant.parse(snapshotAt);
  }

  @Test
  void emptyCart_omitsCurrencyEntirely_andKeepsTheOtherFiveKeys() throws Exception {
    JsonNode body =
        bodyOf(
            mockMvc
                .perform(get("/api/v1/cart").header("Authorization", USER_7))
                .andExpect(status().isOk()));

    assertEquals(EMPTY_CART_KEYS, keysOf(body), "empty cart must not gain a null currency key");
    assertFalse(body.has("currency"), "currency must be OMITTED on an empty cart, never null");
    assertTrue(body.get("items").isArray());
    assertEquals(0, body.get("items").size());
    assertTrue(body.get("subtotal").isIntegralNumber(), "an empty subtotal renders as 0");
    assertEquals("0", body.get("subtotal").asText());
    assertTrue(ISO_8601_UTC.matcher(body.get("updated_at").asText()).matches());
  }

  @Test
  void writePaths_renderTheSameShapeAsTheReadPath() throws Exception {
    JsonNode created = bodyOf(addTwoShirts());
    assertEquals(CART_KEYS, keysOf(created), "201 body must carry the pinned cart key set");
    assertEquals(ITEM_KEYS, keysOf(created.get("items").get(0)));
    assertTrue(ISO_8601_UTC.matcher(created.get("updated_at").asText()).matches());

    JsonNode updated =
        bodyOf(
            mockMvc
                .perform(
                    put("/api/v1/cart/items/42")
                        .header("Authorization", USER_7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk()));
    assertEquals(CART_KEYS, keysOf(updated), "200 body must carry the pinned cart key set");
    assertEquals(ITEM_KEYS, keysOf(updated.get("items").get(0)));
    assertTrue(
        ISO_8601_UTC.matcher(updated.get("items").get(0).get("snapshot_at").asText()).matches());
  }

  private ResultActions addTwoShirts() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER_7)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":2}"))
        .andExpect(status().isCreated());
  }

  private JsonNode bodyOf(ResultActions actions) throws Exception {
    return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
  }

  private static Set<String> keysOf(JsonNode node) {
    Set<String> keys = new LinkedHashSet<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  /** Guards the REST-side casing contract: a camelCase leak anywhere in the tree fails here. */
  private static void assertAllKeysSnakeCase(JsonNode root) {
    List<String> offenders = new ArrayList<>();
    collectNonSnakeCaseKeys(root, offenders);
    assertTrue(offenders.isEmpty(), "REST bodies are snake_case; offending keys: " + offenders);
  }

  private static void collectNonSnakeCaseKeys(JsonNode node, List<String> offenders) {
    if (node.isObject()) {
      node.fieldNames()
          .forEachRemaining(
              name -> {
                if (!SNAKE_CASE.matcher(name).matches()) {
                  offenders.add(name);
                }
                collectNonSnakeCaseKeys(node.get(name), offenders);
              });
    } else if (node.isArray()) {
      node.forEach(child -> collectNonSnakeCaseKeys(child, offenders));
    }
  }
}
