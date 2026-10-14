package ee.shop.identity.service;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of the Redis-backed denylist itself — AuthControllerTest's
 * roundtrip test proves ONE end-to-end scenario (logout, then immediate
 * reuse fails) but never exercises the TTL edge case: a jti whose token has
 * ALREADY expired must be a no-op (nothing left to deny, per the service's
 * own javadoc), not an error or a wasted Redis write.
 */
@Tag("api")
@Tag("security")
class AccessTokenDenylistServiceTest extends IntegrationTest {

    @Autowired AccessTokenDenylistService denylist;

    @Test
    void undenylistedJti_isNotDenylisted() {
        assertThat(denylist.isDenylisted(UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void denylistedJti_isImmediatelyDenylisted() {
        String jti = UUID.randomUUID().toString();
        denylist.denylist(jti, Instant.now().plusSeconds(60));

        assertThat(denylist.isDenylisted(jti)).isTrue();
    }

    @Test
    void denylistingAnAlreadyExpiredJti_isANoOp() {
        String jti = UUID.randomUUID().toString();
        denylist.denylist(jti, Instant.now().minusSeconds(1));

        assertThat(denylist.isDenylisted(jti)).isFalse();
    }

    @Test
    void differentJtis_areIndependentlyTracked() {
        String denylisted = UUID.randomUUID().toString();
        String clean = UUID.randomUUID().toString();
        denylist.denylist(denylisted, Instant.now().plusSeconds(60));

        assertThat(denylist.isDenylisted(denylisted)).isTrue();
        assertThat(denylist.isDenylisted(clean)).isFalse();
    }
}
