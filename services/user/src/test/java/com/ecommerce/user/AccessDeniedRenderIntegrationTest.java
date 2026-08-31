package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ecommerce.user.security.RestAccessDeniedHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract A6 on {@code user}, at RENDER level, because on this service 403 has no HTTP route.
 *
 * <p><strong>Why this class exists in this shape.</strong> Every other service terminates its chain
 * in {@code .anyRequest().denyAll()}, so a probe path that matches only that rule returns 403 and
 * the envelope can be asserted over HTTP. {@code user} terminates in {@code
 * .anyRequest().authenticated()} ({@code SecurityConfig.java:51-52}) and the fleet has zero method
 * security — no {@code @PreAuthorize}, {@code @Secured} or {@code @EnableMethodSecurity} anywhere —
 * so no request an unprivileged caller can make reaches {@link RestAccessDeniedHandler}. An
 * unauthenticated caller gets 401 from the entry point; an authenticated one is permitted.
 *
 * <p>The contract's matrix row for this is filled in with exactly: <em>N/A —
 * RestAccessDeniedHandler unreachable over HTTP (anyRequest().authenticated(), zero method
 * security)</em>. Deleting the row, or the bean, is a FAIL.
 *
 * <p><strong>What is explicitly NOT allowed to make it reachable.</strong> Adding a role rule to
 * {@code SecurityConfig} so that a 403 route exists would be an authorization change smuggled into
 * a version bump — an automatic NO-GO, and the reason this class drives the handler directly
 * instead. The bean is still wired, still constructed by the container, and its rendering is still
 * pinned; what is not pinned is a route, because there is none to pin.
 *
 * <p><strong>What the migration could break here without this class.</strong> The handler shares
 * its whole rendering path with {@link com.ecommerce.user.security.RestAuthenticationEntryPoint} —
 * the same injected mapper, the same {@code ApiError} record, and the same {@code
 * response.getOutputStream()} call that makes {@code user} the fleet's only entry point without a
 * container-appended charset. A Jackson 3 serialization change, a lost {@code @JsonInclude}, or a
 * {@code getOutputStream()} that starts behaving like {@code getWriter()} would move this body
 * while every HTTP row in the suite stayed green, because no HTTP row renders it.
 */
class AccessDeniedRenderIntegrationTest extends AbstractIntegrationTest {

  /**
   * The MockMvc-observed string, matching the 401 rows. This is deliberately NOT asserted equal to
   * the entry point's constant: the two are configured independently and their agreement today is
   * incidental (see {@code RealServletContainerWireIntegrationTest}). This is also not the wire
   * string — a servlet mock appends no charset, exactly as MockMvc does not.
   */
  private static final String RENDERED_CONTENT_TYPE = "application/json";

  @Autowired private RestAccessDeniedHandler accessDeniedHandler;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void accessDeniedHandler_isStillABean_andConstructs() {
    assertTrue(
        accessDeniedHandler != null,
        "the bean must survive the migration even though no route reaches it — deleting it because"
            + " 403 is unreachable is a FAIL, not a cleanup");
  }

  @Test
  void accessDeniedHandler_renders403_withTheExactContentTypeAndTheFourContractKeys()
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    accessDeniedHandler.handle(request, response, new AccessDeniedException("x"));

    assertEquals(403, response.getStatus());
    // Content-Type before the body, so a media-type drift is attributed to the media type rather
    // than surfacing as a parse failure from the assertion that ran first.
    assertEquals(RENDERED_CONTENT_TYPE, response.getContentType(), "403 render Content-Type");

    String raw = response.getContentAsString();
    JsonNode body = objectMapper.readTree(raw);

    Set<String> keys = new HashSet<>();
    body.propertyNames().forEach(keys::add);
    assertEquals(
        Set.of("error", "message", "timestamp", "path"),
        keys,
        "the 403 body must expose exactly the four contract keys, was: " + raw);

    assertEquals("FORBIDDEN", body.get("error").asString());
    assertEquals("Insufficient permissions", body.get("message").asString());
    assertEquals("/api/v1/users/me", body.get("path").asString());
    Instant.parse(body.get("timestamp").asString());
  }

  /**
   * A3, on the one envelope in this service that can violate it. {@code ApiError} is a
   * FIVE-component record whose fifth component {@code fields} is suppressed by a type-level
   * {@code @JsonInclude(NON_NULL)}; a 403 supplies null for it. So {@code "fields":null} appearing
   * here is the exact failure mode A3 forbids, and it is one dropped annotation away at any time.
   *
   * <p>Asserted on the RAW text rather than through the parsed node, because a key whose value is
   * null is indistinguishable from an absent key in most node-level reads — and the key-set
   * assertion above already dominates the parsed view.
   */
  @Test
  void accessDeniedBody_neverCarriesTheSuppressedFifthComponent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    accessDeniedHandler.handle(request, response, new AccessDeniedException("x"));

    String raw = response.getContentAsString();
    assertFalse(
        raw.contains("fields"),
        "ApiError's fifth component must stay suppressed on a 403 — \"fields\":null is a fifth key"
            + " and A3 allows exactly four: "
            + raw);
    assertEquals(
        raw,
        new String(response.getContentAsByteArray(), StandardCharsets.UTF_8),
        "the byte stream and the string view must agree — getOutputStream() writes UTF-8 bytes"
            + " directly, and a charset change here would move the wire body");
  }
}
