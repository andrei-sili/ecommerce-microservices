package com.ecommerce.user.dto;

import com.ecommerce.user.model.User;
import java.time.Instant;
import java.util.Set;

public record ProfileResponse(
    Long id, String email, String name, Set<String> roles, Instant createdAt, Instant updatedAt) {

  public static ProfileResponse from(User user) {
    return new ProfileResponse(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.roleSet(),
        user.getCreatedAt(),
        user.getUpdatedAt());
  }
}
