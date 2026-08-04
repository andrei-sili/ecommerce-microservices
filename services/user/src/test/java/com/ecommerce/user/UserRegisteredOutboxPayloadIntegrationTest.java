package com.ecommerce.user;

import static com.ecommerce.user.support.Iso8601.assertUtcInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.model.OutboxEvent;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pins the {@code UserRegistered} outbox payload: exactly {@code {userId, email, occurredAt}}, in
 * camelCase, with {@code occurredAt} an ISO-8601 UTC string.
 *
 * <p>This is the event side of the casing contract, and it is the opposite convention to the REST
 * bodies (snake_case) two classes over — the two are kept apart deliberately, by a dedicated {@code
 * JsonMapper} in {@code OutboxService} rather than the Boot mapper. Until now the only assertion on
 * this row was that the outbox count grew by one, which a payload renamed to snake_case, reshaped,
 * or carrying a numeric {@code occurredAt} would satisfy unchanged.
 *
 * <p>The row is produced by a real {@code POST /api/v1/auth/register} against real Postgres, so the
 * JSONB text read back here is the same text the relay will ship verbatim as the wire body.
 */
@AutoConfigureMockMvc
class UserRegisteredOutboxPayloadIntegrationTest extends AbstractIntegrationTest {

  private static final String VALID_PASSWORD = "Sup3rSecret12";
  private static final String EMAIL = "outbox-payload@example.com";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @BeforeEach
  void cleanDatabase() {
    refreshTokenRepository.deleteAll();
    outboxEventRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void userRegistered_payloadKeySet_isExactlyUserIdEmailOccurredAt() throws Exception {
    long userId = register();
    JsonNode payload = payloadOf(singleEvent());

    Set<String> keys = new HashSet<>();
    payload.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of("userId", "email", "occurredAt"),
        keys,
        "UserRegistered payload must expose exactly the three contract keys");

    assertEquals(userId, payload.get("userId").asLong(), "userId must identify the new user");
    assertTrue(payload.get("userId").isNumber(), "userId must stay a JSON number");
    assertEquals(EMAIL, payload.get("email").textValue());
  }

  @Test
  void userRegistered_payloadKeys_areCamelCase_notSnakeCase() throws Exception {
    register();
    String raw = singleEvent().getPayload();

    assertTrue(raw.contains("\"userId\""), "payload must use camelCase userId, was: " + raw);
    assertTrue(
        raw.contains("\"occurredAt\""), "payload must use camelCase occurredAt, was: " + raw);
    assertFalse(raw.contains("user_id"), "event payloads are camelCase, never snake_case: " + raw);
    assertFalse(
        raw.contains("occurred_at"), "event payloads are camelCase, never snake_case: " + raw);
  }

  @Test
  void userRegistered_occurredAt_isIso8601UtcString() throws Exception {
    register();

    assertUtcInstant(payloadOf(singleEvent()), "occurredAt");
  }

  @Test
  void userRegistered_rowCarriesTheContractEventAndAggregate() throws Exception {
    long userId = register();
    OutboxEvent event = singleEvent();

    assertEquals("UserRegistered", event.getEventType());
    assertEquals("User", event.getAggregateType());
    assertEquals(String.valueOf(userId), event.getAggregateId());
  }

  private OutboxEvent singleEvent() {
    List<OutboxEvent> events = outboxEventRepository.findAll();
    assertEquals(1, events.size(), "one registration must record exactly one outbox event");
    return events.get(0);
  }

  private JsonNode payloadOf(OutboxEvent event) throws Exception {
    return objectMapper.readTree(event.getPayload());
  }

  private long register() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + EMAIL
                            + "\",\"password\":\""
                            + VALID_PASSWORD
                            + "\",\"name\":\"Olga\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
  }
}
