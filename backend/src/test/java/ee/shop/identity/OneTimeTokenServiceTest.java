package ee.shop.identity;

import ee.shop.common.security.EmailHasher;
import ee.shop.identity.model.OneTimeToken;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.OneTimeTokenService;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("api")
@Tag("security")
class OneTimeTokenServiceTest extends IntegrationTest {

    @Autowired OneTimeTokenService oneTimeTokens;
    @Autowired UserRepository users;
    @Autowired EmailHasher emailHasher;

    private User newUser(String email) {
        return users.save(new User(email, emailHasher.hash(email), "{argon2}$fake", "Test"));
    }

    @Test
    void issuedTokenConsumesExactlyOnce() {
        User u = newUser("otp-basic@test.ee");
        String token = oneTimeTokens.issue(u, OneTimeToken.Purpose.EMAIL_VERIFY, Duration.ofHours(1));

        assertThat(oneTimeTokens.consume(token, OneTimeToken.Purpose.EMAIL_VERIFY))
                .contains(u.getId());

        // Second consume of the same token must fail — otherwise a forwarded
        // or leaked verification/reset link stays valid forever.
        assertThat(oneTimeTokens.consume(token, OneTimeToken.Purpose.EMAIL_VERIFY)).isEmpty();
    }

    @Test
    void consumingForTheWrongPurposeFails() {
        User u = newUser("otp-purpose@test.ee");
        String token = oneTimeTokens.issue(u, OneTimeToken.Purpose.PASSWORD_RESET, Duration.ofHours(1));

        // A password-reset token must not double as an email-verify token,
        // even though they share the same underlying table.
        assertThat(oneTimeTokens.consume(token, OneTimeToken.Purpose.EMAIL_VERIFY)).isEmpty();
        assertThat(oneTimeTokens.consume(token, OneTimeToken.Purpose.PASSWORD_RESET)).contains(u.getId());
    }

    @Test
    void expiredTokenCannotBeConsumed() {
        User u = newUser("otp-expired@test.ee");
        // Negative TTL: already expired the moment it's issued.
        String token = oneTimeTokens.issue(u, OneTimeToken.Purpose.EMAIL_VERIFY, Duration.ofSeconds(-1));

        assertThat(oneTimeTokens.consume(token, OneTimeToken.Purpose.EMAIL_VERIFY)).isEmpty();
    }

    /**
     * The race a naive SELECT-then-UPDATE claim would lose — mirrors
     * TokenRotationTest's concurrent_use test for refresh tokens. Twenty
     * threads present the SAME one-time token at once; exactly one may
     * consume it, or the "single use" guarantee behind email verification
     * and password reset is fiction.
     */
    @Test
    void concurrentConsumptionOfOneTokenLetsExactlyOneWinnerThrough() throws Exception {
        User u = newUser("otp-race@test.ee");
        String token = oneTimeTokens.issue(u, OneTimeToken.Purpose.PASSWORD_RESET, Duration.ofHours(1));

        int threads = 20;
        var pool = Executors.newFixedThreadPool(threads);
        var startLine = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var failures = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLine.await();
                    oneTimeTokens.consume(token, OneTimeToken.Purpose.PASSWORD_RESET)
                            .ifPresentOrElse(id -> successes.incrementAndGet(), failures::incrementAndGet);
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }

        startLine.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successes.get())
                .as("exactly one thread may consume a single-use token")
                .isEqualTo(1);
        assertThat(failures.get()).isEqualTo(threads - 1);
    }
}
