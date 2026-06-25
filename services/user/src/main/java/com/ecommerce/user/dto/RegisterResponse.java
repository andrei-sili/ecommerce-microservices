package com.ecommerce.user.dto;

import com.ecommerce.user.model.User;
import java.time.Instant;

public record RegisterResponse(Long id, String email, String name, Instant createdAt) {

  public static RegisterResponse from(User user) {
    return new RegisterResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
  }
}
