package com.ecommerce.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.cart.repository.CartRepository;
import com.ecommerce.cart.support.AbstractIntegrationTest;
import com.ecommerce.cart.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Full-context guard for framework/dispatcher error mapping on the Cart service: unmapped path,
 * wrong method, bad media type and unreadable body must render the standard {@code {error, message,
 * timestamp, path}} envelope with the correct 4xx status — never a 500 — while security (401),
 * validation (400) and domain ({@link com.ecommerce.cart.exception.ApiException}) mapping stay
 * intact. Runs against the real security chain + dispatcher + Postgres, with a token minted to
 * mirror the User Service contract.
 */
class FrameworkErrorMappingIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void dualAllowlist(DynamicPropertyRegistry registry) {
    // The valid-token rows here mint legacy HS256. Production defaults to RS256-only (main
    // application.yml, Slice 5e phase 3); pin the dual (rollback) allowlist so HS256 stays accepted
    // independent of the shipped default.
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CartRepository cartRepository;

  private static final String USER = TestJwt.bearer(TestJwt.token("7", List.of("USER")));

  @BeforeEach
  void cleanDatabase() {
    cartRepository.deleteAll();
  }

  // 1. Unmapped collection-style path with a valid token -> 404 RESOURCE_NOT_FOUND, full envelope,
  // application/json (not problem+json), no internal leak.
  @Test
  void unmappedPath_returns404_withEnvelope_andJsonContentType_noLeak() throws Exception {
    ResultActions actions = mockMvc.perform(get("/api/v1/carts").header("Authorization", USER));
    MvcResult result = actions.andReturn();

    assertEnvelopeContentType(result);

    actions
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
        .andExpect(jsonPath("$.message", notNullValue()))
        .andExpect(jsonPath("$.timestamp", notNullValue()))
        .andExpect(jsonPath("$.path", is("/api/v1/carts")))
        .andExpect(header().string("Content-Type", containsString("application/json")));

    assertNoLeak(result);
  }

  // 2. Unmapped item-style path with a valid token -> 404 RESOURCE_NOT_FOUND; message must not echo
  // the path.
  @Test
  void unmappedItemPath_returns404_withEnvelope_andNoPathLeak() throws Exception {
    ResultActions actions =
        mockMvc.perform(get("/api/v1/cart/items/99/details").header("Authorization", USER));
    MvcResult result = actions.andReturn();
    assertEnvelopeContentType(result);

    actions
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
        .andExpect(jsonPath("$.message", is("Resource not found")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart/items/99/details")));

    assertNoLeak(result);
  }

  // 3. Wrong HTTP method on a mapped route (/api/v1/cart supports GET + DELETE) -> 405 + Allow.
  @Test
  void wrongMethod_returns405_withAllowHeader() throws Exception {
    ResultActions actions = mockMvc.perform(post("/api/v1/cart").header("Authorization", USER));
    MvcResult result = actions.andReturn();
    assertEnvelopeContentType(result);

    actions
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error", is("METHOD_NOT_ALLOWED")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart")))
        .andExpect(header().string("Allow", containsString("GET")))
        .andExpect(header().string("Allow", containsString("DELETE")));

    assertNoLeak(result);
  }

  // 4. Unsupported request Content-Type on the body route POST /api/v1/cart/items -> 415 + Accept.
  @Test
  void unsupportedMediaType_returns415_withAcceptHeader() throws Exception {
    ResultActions actions =
        mockMvc.perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER)
                .contentType(MediaType.TEXT_PLAIN)
                .content("just some plain text"));
    MvcResult result = actions.andReturn();
    assertEnvelopeContentType(result);

    actions
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error", is("UNSUPPORTED_MEDIA_TYPE")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart/items")))
        .andExpect(header().exists("Accept"));

    assertNoLeak(result);
  }

  // 5. Malformed / unreadable JSON body (regression guard) -> 400 MALFORMED_REQUEST.
  @Test
  void malformedJson_returns400_malformedRequest() throws Exception {
    ResultActions actions =
        mockMvc.perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"));
    MvcResult result = actions.andReturn();
    assertEnvelopeContentType(result);

    actions
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", is("MALFORMED_REQUEST")))
        .andExpect(jsonPath("$.message", is("Request body or parameter is malformed")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart/items")));

    assertNoLeak(result);
  }

  // 6. Bean-Validation failure (regression guard) -> 400 VALIDATION_ERROR naming the field. Cart's
  // envelope carries the offending field inside `message` (no separate `fields` array).
  @Test
  void invalidBody_returns400_validationError_namesField() throws Exception {
    ResultActions actions =
        mockMvc.perform(
            post("/api/v1/cart/items")
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":42,\"quantity\":0}"));
    MvcResult result = actions.andReturn();
    assertEnvelopeContentType(result);

    actions
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart/items")))
        .andExpect(jsonPath("$.message", containsString("quantity")));

    assertNoLeak(result);
  }

  // 7. Security regression guard: no Authorization header -> 401 UNAUTHORIZED via the entry point.
  @Test
  void noToken_returns401_unauthorized() throws Exception {
    ResultActions actions = mockMvc.perform(get("/api/v1/cart"));
    assertEnvelopeContentType(actions.andReturn());

    actions
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart")));
  }

  // 8. Happy-path control: a mapped route with a valid token still returns 200 with the cart.
  @Test
  void mappedRoute_withValidToken_returns200() throws Exception {
    ResultActions actions = mockMvc.perform(get("/api/v1/cart").header("Authorization", USER));
    assertEnvelopeContentType(actions.andReturn());

    actions
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id", is(7)))
        .andExpect(jsonPath("$.items.length()", is(0)));
  }

  // 9. Domain mapping still works through the new advice: a NotFoundException surfaces as 404 with
  // its own machine code (setItemQuantity on an item absent from the auto-created empty cart).
  @Test
  void domainException_cartItemNotFound_returns404() throws Exception {
    ResultActions actions =
        mockMvc.perform(
            put("/api/v1/cart/items/42")
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}"));
    MvcResult result = actions.andReturn();
    assertEnvelopeContentType(result);

    actions
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", is("CART_ITEM_NOT_FOUND")))
        .andExpect(jsonPath("$.path", is("/api/v1/cart/items/42")));

    assertNoLeak(result);
  }

  /**
   * The A7/A9 media-type guard, in EXACT-equality form, run before the jsonPath rows on every row.
   *
   * <p>The older shape — {@code assertFalse(contentType != null &&
   * contentType.contains("problem"))} — is HALF-DEAD. It is live on the branch where Spring renders
   * a {@code ProblemDetail}, but dead on the branch the real drift takes: when the envelope fails
   * to write, {@code getContentType()} returns {@code null}, the first conjunct is false, and the
   * guard passes on a response that has no media type at all. Measured on this service: {@code
   * status=404, ct=null, body=[]}. Exact equality fails on both branches, and names the media type
   * instead of surfacing as a downstream "No value at JSON path $.error".
   */
  private static void assertEnvelopeContentType(MvcResult result) {
    assertThat(result.getResponse().getContentType())
        .as(
            "envelope media type must be exactly application/json: never problem+json, never absent")
        .isEqualTo("application/json");
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
    assertTrue(body.contains("\"error\""), "error body must be the standard envelope: " + body);
  }
}
