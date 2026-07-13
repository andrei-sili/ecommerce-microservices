package com.ecommerce.product.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT validation configuration (Slice 5e dual-accept). Product is a validator only — it never signs
 * tokens, so there is no private key or signing algorithm here.
 *
 * <ul>
 *   <li>{@code secret} — legacy HMAC secret, required only while {@code HS256} stays in the
 *       allowlist (phase 3 drops it once the signer is RS256-only).
 *   <li>{@code acceptedAlgs} — validator allowlist ({@code HS256,RS256} in phase 1). An {@code alg}
 *       outside it is rejected before any key lookup.
 *   <li>{@code publicKeys} — kid → X.509/SPKI PEM path map. The kid is a literal YAML key (e.g.
 *       {@code user-rs256-2026-07}); the path value is env-overridable.
 * </ul>
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
    String secret, List<String> acceptedAlgs, Map<String, String> publicKeys) {}
