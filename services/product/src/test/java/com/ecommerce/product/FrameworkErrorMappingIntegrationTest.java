package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.exception.ApiException;
import com.ecommerce.product.exception.GlobalExceptionHandler;
import com.ecommerce.product.exception.ProductScopedException;
import com.ecommerce.product.support.AbstractIntegrationTest;
import com.ecommerce.product.support.TestJwt;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Full-context guard for framework/dispatcher error mapping: unmapped path, wrong method, bad media
 * type, type mismatch and unreadable body must render the standard {@code {error, message,
 * timestamp, path}} envelope with the correct 4xx status — never a 500 — while security (401/403),
 * validation (400) and the product-scoped reservation envelope stay intact. Runs against the real
 * security chain + dispatcher + Postgres.
 */
class FrameworkErrorMappingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  private static final String ADMIN = TestJwt.bearer(TestJwt.token("1", List.of("ADMIN")));

  // The security regression rows authenticate with a legacy HS256 admin token; phase-3 flipped the
  // yml default to RS256-only, so pin the dual allowlist to keep the HS256 path reachable here.
  @DynamicPropertySource
  static void dualAllowlist(DynamicPropertyRegistry registry) {
    registry.add("security.jwt.accepted-algs", () -> "HS256,RS256");
  }

  // 1. Unmapped collection-style path (public read) -> 404 RESOURCE_NOT_FOUND, full envelope,
  // application/json (not problem+json), no internals leaked.
  @Test
  void unmappedPath_returns404_withEnvelope_andJsonContentType_noLeak() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/inventory"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
            .andExpect(jsonPath("$.message", is("Resource not found")))
            .andExpect(jsonPath("$.timestamp", notNullValue()))
            .andExpect(jsonPath("$.path", is("/api/v1/inventory")))
            .andExpect(header().string("Content-Type", containsString("application/json")))
            .andReturn();

    String contentType = result.getResponse().getContentType();
    assertFalse(
        contentType != null && contentType.contains("problem"),
        "framework error must not use application/problem+json, was: " + contentType);
    assertNoLeak(result);
  }

  // 2. Unmapped item/by-id-style sibling (public read) -> 404 RESOURCE_NOT_FOUND, message must not
  // echo the path or the "No static resource" internal.
  @Test
  void unmappedItemPath_returns404_withEnvelope_andNoPathLeak() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/products/42/reviews"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("RESOURCE_NOT_FOUND")))
            .andExpect(jsonPath("$.message", is("Resource not found")))
            .andExpect(jsonPath("$.path", is("/api/v1/products/42/reviews")))
            .andReturn();

    assertNoLeak(result);
  }

  // 3. Wrong HTTP method on a mapped route -> 405 + Allow header listing the permitted methods.
  @Test
  void wrongMethod_returns405_withAllowHeader() throws Exception {
    MvcResult result =
        mockMvc
            .perform(put("/api/v1/categories").header("Authorization", ADMIN))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("METHOD_NOT_ALLOWED")))
            .andExpect(jsonPath("$.path", is("/api/v1/categories")))
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(header().string("Allow", containsString("POST")))
            .andReturn();

    assertNoLeak(result);
  }

  // 4. Unsupported request Content-Type on a body route -> 415 + Accept header present.
  @Test
  void unsupportedMediaType_returns415_withAcceptHeader() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/categories")
                    .header("Authorization", ADMIN)
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("just some plain text"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("UNSUPPORTED_MEDIA_TYPE")))
            .andExpect(jsonPath("$.path", is("/api/v1/categories")))
            .andExpect(header().exists("Accept"))
            .andReturn();

    assertNoLeak(result);
  }

  // 5. Malformed / unreadable JSON body (regression guard) -> 400 MALFORMED_REQUEST.
  @Test
  void malformedJson_returns400_malformedRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/categories")
                    .header("Authorization", ADMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("MALFORMED_REQUEST")))
            .andExpect(jsonPath("$.message", is("Request body or parameter is malformed")))
            .andExpect(jsonPath("$.path", is("/api/v1/categories")))
            .andReturn();

    assertNoLeak(result);
  }

  // 6. Bean-Validation failure (regression guard) -> 400 VALIDATION_ERROR naming the field.
  @Test
  void invalidBody_returns400_validationError_namesField() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/categories")
                    .header("Authorization", ADMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"\",\"slug\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
            .andExpect(jsonPath("$.path", is("/api/v1/categories")))
            .andExpect(jsonPath("$.message", containsString("name")))
            .andReturn();

    assertNoLeak(result);
  }

  // 7. Type mismatch on a query param -> 400 MALFORMED_REQUEST (the split of the old grouped
  // handler
  // still maps it; also proves the advice started without an ambiguous-mapping crash).
  @Test
  void typeMismatchQueryParam_returns400_malformedRequest() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/products").param("page", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error", is("MALFORMED_REQUEST")))
            .andExpect(jsonPath("$.path", is("/api/v1/products")))
            .andReturn();

    assertNoLeak(result);
  }

  // 8. Security regression: no Authorization header on a JWT-gated write -> 401 UNAUTHORIZED.
  @Test
  void noToken_onWriteRoute_returns401_unauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel\",\"slug\":\"apparel\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.path", is("/api/v1/categories")));
  }

  // 9. Security regression: a malformed (non-parseable) token on a write route -> 401 UNAUTHORIZED.
  @Test
  void invalidToken_onWriteRoute_returns401_unauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .header("Authorization", "Bearer not-a-real-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel\",\"slug\":\"apparel\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
  }

  // 10. Happy-path control: a mapped public read still returns 200 with its real body.
  @Test
  void mappedRoute_returns200() throws Exception {
    mockMvc
        .perform(get("/api/v1/categories"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray());
  }

  // 11. Domain regression: a ProductScopedException path still renders its product_id envelope —
  // the framework refactor must not disturb the custom @ExceptionHandler handlers.
  @Test
  void productScopedException_stillReturnsProductIdEnvelope() throws Exception {
    String body =
        """
        { "order_id":"%s", "items":[{"product_id":%d,"quantity":1}] }
        """
            .formatted(UUID.randomUUID(), 999_999L);

    mockMvc
        .perform(
            post("/api/v1/inventory/reservations")
                .header("X-Internal-Api-Key", INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error", is("PRODUCT_NOT_FOUND")))
        .andExpect(jsonPath("$.product_id", is(999_999)));
  }

  // Sanity that the internal-key gate is unaffected: a release with the key is a 204 no-op.
  @Test
  void reservationRelease_withInternalKey_returns204() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/inventory/reservations/" + UUID.randomUUID())
                .header("X-Internal-Api-Key", INTERNAL_API_KEY))
        .andExpect(status().isNoContent());
  }

  /**
   * A10, structural half. The catch-all {@code @ExceptionHandler(Exception.class)} must stay the
   * only 500 producer, and no standalone handler may declare a type the {@link
   * ResponseEntityExceptionHandler} base class already maps — that collision is a STARTUP crash
   * ("Ambiguous @ExceptionHandler"), not a test failure, so it takes the service down rather than
   * reddening a row.
   *
   * <p>The base-mapped set is read reflectively from the framework on the classpath instead of
   * being hard-coded, so this goes red if a future Spring version ADDS a mapping that collides with
   * one of ours. Today only SUPERtypes are base-mapped — {@code
   * MethodArgumentTypeMismatchException} sits under {@code TypeMismatchException} — which is
   * exactly why our standalone handler for the subtype is legal, and exactly what a base-class
   * change could silently invalidate.
   *
   * <p><b>The standalone set is pinned EXACTLY, not by {@code contains}.</b> A subset assertion
   * discharges the "no ambiguity" half but not the "single 500 producer" half: a brand-new
   * {@code @ExceptionHandler} returning 500 satisfies {@code contains(Exception.class)} while
   * adding the second producer the row forbids.
   *
   * <p><b>"Only 500" is narrower than "only 5xx", and on product the gap is occupied.</b> {@link
   * GlobalExceptionHandler#handleApi} renders {@code ex.getStatus()} and {@code
   * ProductScopedException} carries its own, so a standalone handler here can emit any status its
   * exception names. The row's letter is intact — the catch-all remains the only producer of 500 —
   * and the assertion message should be read as written: a new standalone handler is a candidate
   * second 500 producer, not a claim that no standalone handler may be 5xx.
   *
   * <p>Scope, so a green is not read louder than it earns. This pins WHICH types get a standalone
   * handler; it never executes them, so it cannot see one of the three non-catch-all handlers being
   * changed to render 500. Those statuses are held by the behavioural rows in this class and in
   * {@code ErrorEnvelopePinIT} / {@code ReservationIT}.
   */
  @Test
  void noStandaloneHandlerIsAmbiguousWithTheBaseClass_andTheCatchAllIsTheOnly500() {
    Set<Class<?>> baseMapped = mappedTypes(ResponseEntityExceptionHandler.class);
    Set<Class<?>> standalone = mappedTypes(GlobalExceptionHandler.class);

    // Both halves of the reflection must actually have found something: an empty set would make
    // doesNotContainAnyElementsOf trivially true and this whole row vacuous.
    assertThat(baseMapped).as("reflection must actually find the base mappings").isNotEmpty();
    assertThat(standalone).as("reflection must actually find our handlers").isNotEmpty();

    assertThat(standalone)
        .as(
            "a standalone @ExceptionHandler declaring a type the base class already maps is an"
                + " ambiguous mapping and fails startup")
        .doesNotContainAnyElementsOf(baseMapped);

    assertThat(standalone)
        .as(
            "the catch-all must remain, and remain the ONLY standalone handler — a new one here is"
                + " a candidate second 500 producer and must be justified, not absorbed")
        .containsExactlyInAnyOrder(
            ProductScopedException.class,
            ApiException.class,
            MethodArgumentTypeMismatchException.class,
            Exception.class);
  }

  private static Set<Class<?>> mappedTypes(Class<?> handler) {
    return Arrays.stream(handler.getDeclaredMethods())
        .map(m -> m.getAnnotation(ExceptionHandler.class))
        .filter(Objects::nonNull)
        .flatMap(a -> Arrays.stream(a.value()))
        .collect(Collectors.toSet());
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
