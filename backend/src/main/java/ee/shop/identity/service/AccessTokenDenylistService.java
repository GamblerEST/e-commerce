package ee.shop.identity.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Backs the "instant logout" feature: explicit /auth/logout denylists the
 * CURRENT access token's jti so JwtAuthFilter rejects it immediately, instead
 * of it staying valid for up to its remaining 15-minute TTL. See the comment
 * on TokenService.accessToken(...) for why this stays narrow — logout only,
 * not a general revocation mechanism.
 *
 * The TTL on the Redis key is set to the token's OWN remaining lifetime, never
 * longer: once the JWT itself would have expired anyway, there is nothing
 * left to deny, and Redis reclaims the key on its own.
 */
@Service
public class AccessTokenDenylistService {

    private static final String KEY_PREFIX = "auth:denylist:jti:";

    private final StringRedisTemplate redis;

    public AccessTokenDenylistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void denylist(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return; // already expired on its own; nothing to deny
        }
        redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
    }

    public boolean isDenylisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }
}
