package com.ecommerce.cart.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT validation configuration. Cart never issues tokens, so there is no signing key or signing
 * algorithm here — Slice 5e only adds dual-accept validation material:
 *
 * <ul>
 *   <li>{@code secret} — legacy HMAC secret, required only while {@code HS256} is in the allowlist.
 *   <li>{@code acceptedAlgs} — validator allowlist ({@code HS256,RS256} in phase 1). An {@code alg}
 *       outside it is rejected before any key lookup.
 *   <li>{@code publicKeys} — kid → X.509/SPKI PEM path map. The kid is a literal YAML key (e.g.
 *       {@code user-rs256-2026-07}); the path value is env-overridable.
 * </ul>
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
    String secret, List<String> acceptedAlgs, Map<String, String> publicKeys) {}
