package ee.shop.common.security;

import ee.shop.common.error.AppException;
import ee.shop.config.RateLimitProperties;
import ee.shop.support.IntegrationTest;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the REAL RateLimitService against the REAL Redis container
 * (Testcontainers, shared with IntegrationTest), wired directly rather than
 * through Spring — IntegrationTest overrides application-wide rate-limit
 * capacities to huge test-only values so functional/happy-path tests never
 * trip the limiter on their shared MockMvc IP (see IntegrationTest). That
 * would make it impossible to observe real limiting behaviour through the
 * Spring-managed bean, so this test builds its own RateLimitService instance
 * with small, known capacities, pointed at the same Redis instance.
 *
 * Each test uses its own random bucket key (route name) so runs never
 * collide with each other or with anything the Spring-wired service does
 * against the same Redis container.
 */
@Tag("api")
@Tag("security")
class RateLimitServiceTest extends IntegrationTest {

    private RedisClient rawClient;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        RedisURI uri = RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379)).build();
        rawClient = RedisClient.create(uri);
        StatefulRedisConnection<String, byte[]> connection =
                rawClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        ProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(connection).build();

        RateLimitProperties.Limit small = new RateLimitProperties.Limit(5, Duration.ofMinutes(1));
        // One Limit per record component. A positional constructor like this
        // breaks loudly whenever a bucket is added, which is the behaviour we
        // want — a new limit that nobody wired up here would otherwise be
        // untested.
        RateLimitProperties props = new RateLimitProperties(
                small, small, small, small, small, small, small, small, small, small, small);
        service = new RateLimitService(proxyManager, props);
    }

    @AfterEach
    void tearDown() {
        rawClient.shutdown();
    }

    @Test
    void allowsExactlyCapacityRequestsThenRejects() {
        String ip = uniqueIp();

        for (int i = 0; i < 5; i++) {
            service.enforceLogin(ip); // must not throw
        }

        assertThatThrownBy(() -> service.enforceLogin(ip))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void limitIsPerRouteNotGlobal() {
        String ip = uniqueIp();

        for (int i = 0; i < 5; i++) {
            service.enforceLogin(ip);
        }
        assertThatThrownBy(() -> service.enforceLogin(ip)).isInstanceOf(AppException.class);

        // A DIFFERENT route for the same IP has its own, untouched bucket.
        service.enforceRegister(ip); // must not throw
    }

    @Test
    void limitIsPerIpNotGlobal() {
        String ipA = uniqueIp();
        String ipB = uniqueIp();

        for (int i = 0; i < 5; i++) {
            service.enforceLogin(ipA);
        }
        assertThatThrownBy(() -> service.enforceLogin(ipA)).isInstanceOf(AppException.class);

        // A different client IP is unaffected by ipA's exhausted bucket.
        service.enforceLogin(ipB); // must not throw
    }

    /**
     * The concurrency case that matters in production: many requests from
     * the same attacker IP arriving at once must never let more than
     * `capacity` of them through. Mirrors the attack-it-for-real pattern
     * used by TokenRotationTest / RecoveryCodeServiceTest elsewhere in this
     * suite rather than trusting the happy-path sequential test above alone.
     */
    @Test
    void concurrentHammering_neverAdmitsMoreThanCapacity() throws Exception {
        String ip = uniqueIp();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    service.enforceLogin(ip);
                    admitted.incrementAndGet();
                } catch (AppException e) {
                    rejected.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(admitted.get()).isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(threads - 5);
    }

    /**
     * The whole point of the IP+user dual bucket (RateLimitService.
     * enforceIpAndUser): an authenticated attacker exhausting their OWN
     * per-user bucket must still be blocked even from a completely fresh
     * IP — rotating source IPs must not be a bypass for a logged-in
     * attacker. This is the case IP-only limiting (the previous design)
     * could never catch.
     */
    @Test
    void ipAndUserBuckets_areIndependent_andBothEnforced() {
        UUID userId = UUID.randomUUID();
        String firstIp = uniqueIp();

        for (int i = 0; i < 5; i++) {
            service.enforceCheckout(firstIp, userId);
        }
        // Exhausted for this user — a FRESH ip does not bypass it.
        String secondIp = uniqueIp();
        assertThatThrownBy(() -> service.enforceCheckout(secondIp, userId))
                .isInstanceOf(AppException.class);

        // Conversely, a DIFFERENT user from the exhausted IP still has
        // their own fresh per-user bucket — but the shared IP bucket is
        // already exhausted too, so it's the IP-based rejection at play
        // here; provable by using a not-yet-touched IP for a fresh user.
        UUID otherUser = UUID.randomUUID();
        String freshIp = uniqueIp();
        service.enforceCheckout(freshIp, otherUser); // must not throw
    }

    @Test
    void guestCheckout_withNullUserId_fallsBackToIpOnly() {
        String ip = uniqueIp();

        for (int i = 0; i < 5; i++) {
            service.enforceCheckout(ip, null); // guest — no user dimension
        }
        assertThatThrownBy(() -> service.enforceCheckout(ip, null))
                .isInstanceOf(AppException.class);
    }

    private static String uniqueIp() {
        // Not a real routable address, just a unique bucket key per test run.
        return "203.0.113.test-" + UUID.randomUUID();
    }
}
