package com.ecommerce.user.event;

import java.time.Instant;

/** Payload for the {@code UserRegistered} event (contract: userId, email, occurredAt). */
public record UserRegisteredPayload(Long userId, String email, Instant occurredAt) {}
