package com.ecommerce.order.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT validation configuration (Slice 5e dual-accept). Order never issues tokens, so there is no
 * signing key or {@code signing-alg} here — only the material needed to validate inbound tokens:
 *
 * <ul>
 *   <li>{@code secret} — legacy HS256 HMAC secret; required only while {@code HS256} is in the
 *       allowlist. Shared with the User Service issuer until phase 3 deletes it.
 *   <li>{@code acceptedAlgs} — validator allowlist ({@code HS256,RS256} in phase 1). An {@code alg}
 *       outside it is rejected before any key lookup.
 *   <li>{@code publicKeys} — kid → X.509/SPKI PEM path map. The kid is a literal YAML key (e.g.
 *       {@code user-rs256-2026-07}); the path value is env-overridable.
 * </ul>
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
    String secret, List<String> acceptedAlgs, Map<String, String> publicKeys) {}
