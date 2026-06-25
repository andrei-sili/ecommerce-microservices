package com.ecommerce.product.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.product.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  @Test
  void rejectsBlankSecret() {
    assertThatThrownBy(() -> new JwtService("  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void rejectsSecretShorterThan32Bytes() {
    assertThatThrownBy(() -> new JwtService("short-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void parsesValidTokenWithRoles() {
    JwtService service = new JwtService(TestJwt.SECRET);
    String token = TestJwt.token("42", List.of("ADMIN"));

    JwtService.AuthenticatedUser user = service.parse(token);

    assertThat(user.subject()).isEqualTo("42");
    assertThat(user.roles()).containsExactly("ADMIN");
  }
}
