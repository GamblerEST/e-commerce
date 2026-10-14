package ee.shop.identity;

import ee.shop.common.security.EmailHasher;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.RecoveryCodeService;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("api")
@Tag("security")
class RecoveryCodeServiceTest extends IntegrationTest {

    @Autowired RecoveryCodeService recoveryCodes;
    @Autowired UserRepository users;
    @Autowired EmailHasher emailHasher;

    private User newUser(String email) {
        return users.save(new User(email, emailHasher.hash(email), "{argon2}$fake", "Test"));
    }

    @Test
    void regenerateReturnsTenUniqueCodes() {
        User u = newUser("recovery-basic@test.ee");
        List<String> codes = recoveryCodes.regenerate(u.getId());

        assertThat(codes).hasSize(10);
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void eachCodeConsumesExactlyOnce() {
        User u = newUser("recovery-single-use@test.ee");
        List<String> codes = recoveryCodes.regenerate(u.getId());
        String code = codes.get(0);

        assertThat(recoveryCodes.consume(u.getId(), code)).isTrue();
        // Reusing it — a leaked/photographed recovery code must not work twice.
        assertThat(recoveryCodes.consume(u.getId(), code)).isFalse();
    }

    @Test
    void wrongCodeIsRejected() {
        User u = newUser("recovery-wrong@test.ee");
        recoveryCodes.regenerate(u.getId());

        assertThat(recoveryCodes.consume(u.getId(), "NOTRE-ALCODE")).isFalse();
    }

    @Test
    void regeneratingReplacesThePreviousSet() {
        User u = newUser("recovery-regenerate@test.ee");
        List<String> first = recoveryCodes.regenerate(u.getId());
        List<String> second = recoveryCodes.regenerate(u.getId());

        // The old set must no longer work once a new set has been issued.
        assertThat(recoveryCodes.consume(u.getId(), first.get(0))).isFalse();
        assertThat(recoveryCodes.consume(u.getId(), second.get(0))).isTrue();
    }

    /**
     * Same race every other single-use credential in this codebase is tested
     * against (TokenRotationTest, OneTimeTokenServiceTest): N threads present
     * the SAME recovery code at once; exactly one may consume it. A scan +
     * unguarded save() here would let concurrent logins burn through several
     * "single-use" codes with one code.
     */
    @Test
    void concurrentConsumptionOfOneCodeLetsExactlyOneWinnerThrough() throws Exception {
        User u = newUser("recovery-race@test.ee");
        String code = recoveryCodes.regenerate(u.getId()).get(0);

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
                    if (recoveryCodes.consume(u.getId(), code)) {
                        successes.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }

        startLine.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successes.get()).as("exactly one thread may consume a single-use recovery code").isEqualTo(1);
        assertThat(failures.get()).isEqualTo(threads - 1);
    }
}
