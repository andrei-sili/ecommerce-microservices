package com.ecommerce.user;

import static com.ecommerce.user.support.Iso8601.assertUtcInstant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.user.event.OutboxService;
import com.ecommerce.user.model.OutboxEvent;
import com.ecommerce.user.repository.OutboxEventRepository;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

  /** Fixed inputs for the golden fixture — the clock, the id and the address must not vary. */
  private static final long GOLDEN_USER_ID = 4242L;

  private static final String GOLDEN_EMAIL = "golden@example.com";
  private static final String GOLDEN_OCCURRED_AT = "2026-06-26T10:00:00Z";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private OutboxService outboxService;

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
    payload.propertyNames().forEach(keys::add);
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

  /**
   * Contract B17: the written payload is compared against a committed BASELINE, not against itself.
   *
   * <p>Every other row in this class derives its expectation from the same run that produced the
   * value — the key set from the node just parsed, the id from the response just received. That is
   * the right shape for a shape assertion and the wrong shape for a serialization pin: it proves
   * the payload is self-consistent, never that it still looks the way it looked before the Jackson
   * major. This row is the only one that can fail on a change nobody predicted, because the
   * expected bytes were written down first and live in git.
   *
   * <p>Driven through the real {@link com.ecommerce.user.event.OutboxService} rather than through
   * {@code POST /register}, for the one reason that makes a golden fixture possible at all: the
   * service takes {@code occurredAt} as a parameter, so the clock can be fixed without a {@code
   * Clock} bean. Both other varying inputs (user id, email) are fixed too.
   *
   * <p><strong>These are the WIRE bytes, and they are NOT what Jackson emitted</strong> (contract
   * §4b). {@code outbox_events.payload} is {@code JSONB}, and Postgres rewrites jsonb text on
   * insert: it reorders keys by (length, then bytes) and inserts a space after every {@code :} and
   * {@code ,}. That is why the fixture reads {@code email, userId, occurredAt} — 5, 6 and 10
   * characters — rather than the record's component order, and why it is spaced. The first version
   * of this fixture was written from the serializer's shape, and this row caught it: the row
   * working as intended before it had ever guarded anything. The wire bytes are the right thing to
   * pin because they are the only text a consumer ever parses.
   *
   * <p><strong>Stated limit, so an empty diff is not read louder than it earns.</strong> Postgres
   * has already reordered the keys, so this pin is BLIND to Jackson's own key order: a green here
   * is evidence about casing, key presence, number form and date format, and about nothing else.
   * §4b names that exact inference as the vacuous one to avoid. On this service the serializer's
   * output is not separately observable — the mapper and {@code serialize} are both private to
   * {@code OutboxService}, and user has no relay to intercept — so no canary is added rather than
   * one that would only appear to close the gap. A4 declares key order non-binding anyway, so what
   * is unpinned here is unpinned deliberately.
   */
  @Test
  void userRegistered_payloadBytes_matchTheCommittedGoldenFixture() throws Exception {
    outboxService.recordUserRegistered(
        GOLDEN_USER_ID, GOLDEN_EMAIL, Instant.parse(GOLDEN_OCCURRED_AT));

    String written = singleEvent().getPayload();
    String golden =
        new String(
                getClass().getResourceAsStream("/golden/user-registered.json").readAllBytes(),
                StandardCharsets.UTF_8)
            .trim();

    assertFalse(
        golden.isEmpty(),
        "the golden fixture must not be empty — an empty baseline"
            + " satisfies its own diff and proves nothing");
    assertEquals(
        golden,
        written,
        "the UserRegistered payload no longer matches the fixture captured before the Jackson"
            + " major. If this is a deliberate contract change it belongs in api_contracts.md and"
            + " in every consumer, not in a re-captured fixture.");
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
