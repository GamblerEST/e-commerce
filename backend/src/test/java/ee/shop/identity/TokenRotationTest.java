package ee.shop.identity;

import ee.shop.common.security.EmailHasher;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.RefreshTokenRepository;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.TokenService;
import ee.shop.identity.service.TokenService.TokenPair;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@Tag("api")
@Tag("security")
@Tag("flow")
class TokenRotationTest extends IntegrationTest {

    @Autowired TokenService tokenService;
    @Autowired UserRepository users;
    @Autowired EmailHasher emailHasher;
    @Autowired RefreshTokenRepository tokens;

    private User newUser(String email) {
        return users.save(new User(email, emailHasher.hash(email), "{argon2}$fake", "Test"));
    }

    @Test
    void rotation_issues_a_new_token_and_retires_the_old_one() {
        User u = newUser("rotate@test.ee");

        TokenPair first = tokenService.issueNewFamily(u, "junit", "127.0.0.1");
        TokenPair second = tokenService.rotate(first.refreshToken(), u, "junit", "127.0.0.1");

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        // The old token is now spent — using it again must fail.
        assertThatThrownBy(() -> tokenService.rotate(first.refreshToken(), u, "junit", "127.0.0.1"))
                .hasMessageContaining("revoked");
    }

    @Test
    void replaying_a_used_token_revokes_the_entire_family() {
        User u = newUser("replay@test.ee");

        // Legitimate chain: A -> B -> C
        TokenPair a = tokenService.issueNewFamily(u, "junit", "1.1.1.1");
        TokenPair b = tokenService.rotate(a.refreshToken(), u, "junit", "1.1.1.1");
        TokenPair c = tokenService.rotate(b.refreshToken(), u, "junit", "1.1.1.1");

        // C is the live token and works fine right now.
        // Now an attacker replays A — a token that was rotated two steps ago.
        // Honest clients never do this.
        assertThatThrownBy(() -> tokenService.rotate(a.refreshToken(), u, "attacker", "6.6.6.6"))
                .hasMessageContaining("revoked");

        // THE POINT: the victim's still-valid token C must ALSO be dead now.
        // We could not tell attacker from victim, so we killed the whole family.
        assertThatThrownBy(() -> tokenService.rotate(c.refreshToken(), u, "junit", "1.1.1.1"))
                .hasMessageContaining("revoked");

        // Every token in the family carries the forensic reason.
        var all = tokens.findAll().stream()
                .filter(t -> t.getUserId().equals(u.getId()))
                .toList();
        assertThat(all).hasSize(3);
        assertThat(all).allMatch(t -> "reuse_detected".equals(t.getRevokedReason()));
    }

    @Test
    void a_revoked_family_does_not_affect_other_sessions() {
        User u = newUser("multisession@test.ee");

        // Same user, two devices = two independent families.
        TokenPair phone  = tokenService.issueNewFamily(u, "phone",  "1.1.1.1");
        TokenPair laptop = tokenService.issueNewFamily(u, "laptop", "2.2.2.2");

        TokenPair phone2 = tokenService.rotate(phone.refreshToken(), u, "phone", "1.1.1.1");

        // Compromise the phone family by replaying its spent token.
        assertThatThrownBy(() -> tokenService.rotate(phone.refreshToken(), u, "thief", "6.6.6.6"))
                .hasMessageContaining("revoked");

        // The laptop is a different family and must survive. Logging someone out
        // of every device because one device's token leaked would be its own
        // kind of failure.
        assertThatCode(() -> tokenService.rotate(laptop.refreshToken(), u, "laptop", "2.2.2.2"))
                .doesNotThrowAnyException();
    }

    /**
     * The race that a naive SELECT-then-UPDATE implementation would lose.
     *
     * Twenty threads present the SAME refresh token at the same instant.
     * Exactly ONE must succeed. If two succeed, the atomic claim is broken and
     * reuse detection is worthless — an attacker racing the victim would slip
     * through undetected.
     */
    @Test
    void concurrent_use_of_one_token_lets_exactly_one_winner_through() throws Exception {
        User u = newUser("race@test.ee");
        TokenPair t = tokenService.issueNewFamily(u, "junit", "1.1.1.1");

        int threads = 20;
        var pool = Executors.newFixedThreadPool(threads);
        var startLine = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var failures  = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLine.await();                 // all threads fire together
                    tokenService.rotate(t.refreshToken(), u, "junit", "1.1.1.1");
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }

        startLine.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successes.get())
                .as("exactly one thread may claim a single-use token")
                .isEqualTo(1);
        assertThat(failures.get()).isEqualTo(threads - 1);
    }
}
