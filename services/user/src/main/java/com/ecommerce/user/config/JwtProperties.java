package com.ecommerce.user.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT configuration. Slice 5e adds dual-accept validation material:
 *
 * <ul>
 *   <li>{@code signingAlg} — issuer algorithm; stays {@code HS256} in phase 1 (the RS256 flip is
 *       phase 2). Read only to decide whether the legacy HMAC secret is required at startup.
 *   <li>{@code acceptedAlgs} — validator allowlist ({@code HS256,RS256} in phase 1). An {@code alg}
 *       outside it is rejected before any key lookup.
 *   <li>{@code privateKeyPath} — PKCS#8 PEM signing key, mounted into user-service only. Loaded and
 *       validated at startup so the phase-2 signer flip is config-only; not used for signing yet.
 *   <li>{@code publicKeys} — kid → X.509/SPKI PEM path map. The kid is a literal YAML key (e.g.
 *       {@code user-rs256-2026-07}); the path value is env-overridable.
 * </ul>
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
    String secret,
    long accessTokenTtlSeconds,
    long refreshTokenTtlSeconds,
    String issuer,
    String signingAlg,
    List<String> acceptedAlgs,
    String privateKeyPath,
    Map<String, String> publicKeys) {}
