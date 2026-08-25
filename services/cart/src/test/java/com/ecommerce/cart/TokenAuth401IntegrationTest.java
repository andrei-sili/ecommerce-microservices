package com.ecommerce.cart;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ecommerce.cart.support.JwtTestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the HTTP-level {@code 401} contract of the JWT filter chain on the canonical protected
 * endpoint {@code GET /api/v1/cart} (the #35/#36 pinned suite, ported to cart with RS256 fixtures):
 * a missing header, every non-{@code Bearer} auth scheme, and a malformed token are all rejected
 * with the standard four-field envelope — same key-set, cause never leaks — AND leave observability
 * flat (no counter increment, no {@code jwt.audit} line). Exercises the REAL filter chain through
 * full-context MockMvc, so a regression in strict scheme parsing or envelope rendering turns red.
 */
class TokenAuth401IntegrationTest extends AbstractDualAcceptTest {

  private static final long USER_ID = 7L;

  /**
   * Anti-vacuous control: a token minted through the exact RS256 test path IS accepted. Without it,
   * the 401s below could be a broken fixture (a wrong key would 401 everything and look like a
   * passing contract).
   */
  @Test
  void validRs256Token_isAccepted_provingSchemeMatchingNotBrokenFixture() throws Exception {
    expectCartOk(
        JwtTestKeys.mintRs256(USER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A), USER_ID);
  }

  /**
   * Every case carries a fully VALID RS256 token — only the scheme is wrong — so this pins scheme
   * matching, not token validity. {@code bearer} (lowercase) and {@code Bearer}-glued are
   * deliberate: the contract mandates the strict, case-sensitive {@code "Bearer "} prefix.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "Basic scheme,Basic dXNlcjpwYXNz",
    "Token scheme,Token {token}",
    "lowercase bearer scheme,bearer {token}",
    "Bearer glued to token,Bearer{token}",
    "raw token without scheme,{token}"
  })
  void nonBearerScheme_returns401_flat(String label, String headerTemplate) throws Exception {
    String headerValue =
        headerTemplate.replace(
            "{token}", JwtTestKeys.mintRs256(USER_ID, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A));
    expectUnauthorizedForHeader(headerValue);
  }

  /**
   * No {@code Authorization} header at all — the filter never authenticates, entry point renders.
   */
  @Test
  void missingAuthorizationHeader_returns401_flat() throws Exception {
    expectUnauthorizedWithoutAuth();
  }

  /** Correct {@code Bearer} scheme but a token that is not a parseable JWT. */
  @Test
  void malformedBearerToken_returns401_flat() throws Exception {
    expectUnauthorizedForHeader("Bearer not.a.jwt");
  }

  /**
   * A9: {@code path} is echoed from {@code request.getRequestURI()}, which is attacker-controlled
   * and carries the raw percent-encoded bytes rather than a decoded string. Boot 4.1 ships Tomcat
   * 11 / Servlet 6.1, so a container that starts decoding (or re-encoding) the segment changes the
   * envelope for every non-ASCII URL — a drift no ASCII path can expose.
   */
  @Test
  void nonAsciiPathSegment_returns401_andEchoesTheUriPercentEncoded() throws Exception {
    expectUnauthorizedEnvelopeForPath(
        mockMvc.perform(get("/api/v1/cart/caf\u00e9")), "/api/v1/cart/caf%C3%A9");
  }
}
