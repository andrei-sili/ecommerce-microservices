package com.ecommerce.user.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.springframework.test.web.servlet.MvcResult;

/** Single source of truth for the wire form of this service's error envelope. */
public final class ErrorEnvelopes {

  /** The only {@code Content-Type} an error body here is allowed to carry, byte for byte. */
  public static final String JSON = "application/json";

  private ErrorEnvelopes() {}

  /**
   * Fails unless the response carries exactly {@link #JSON}.
   *
   * <p>Exactness is what does the work, and it replaces {@code contentTypeCompatibleWith}, which
   * this suite used at every error site. That matcher is blind twice over: it accepts a {@code
   * charset} parameter, so the two rendering paths could start disagreeing about one unnoticed
   * (this service is the only one whose entry point writes via {@code getOutputStream()} rather
   * than {@code getWriter()}, and the container's charset handling of the two differs), and it
   * treats {@code application/problem+json} as compatible via the {@code +json} suffix, so the
   * framework's own error representation could take over the envelope invisibly.
   */
  public static void assertJsonNotProblem(MvcResult result) {
    String contentType = result.getResponse().getContentType();
    assertFalse(
        contentType != null && contentType.contains("problem"),
        "error envelope must not use application/problem+json, was: " + contentType);
    assertEquals(JSON, contentType, "error envelope Content-Type must be exactly " + JSON);
  }
}
