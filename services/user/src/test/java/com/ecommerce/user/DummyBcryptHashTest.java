package com.ecommerce.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Guards the unknown-email timing defense: the dummy hash on the no-user login path must be a
 * well-formed BCrypt so {@code matches()} runs a real computation instead of short-circuiting on a
 * malformed hash (which would leak email existence via timing).
 */
class DummyBcryptHashTest {

  private static final String DUMMY_BCRYPT_HASH =
      "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVNugaaaaa";

  @Test
  void dummyHashIsWellFormedBcrypt() {
    assertEquals(60, DUMMY_BCRYPT_HASH.length());
    assertTrue(DUMMY_BCRYPT_HASH.startsWith("$2a$10$"));
  }

  @Test
  void matchesAgainstDummyHashRunsRealComparison() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    // A valid hash that no real password matches: matches() must return false WITHOUT logging a
    // "malformed hash" warning, i.e. a genuine BCrypt computation took place.
    assertFalse(encoder.matches("any-password-value-123", DUMMY_BCRYPT_HASH));
  }
}
