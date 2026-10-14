package ee.shop.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@Tag("security")
class JwtPropertiesTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    void unresolvedPlaceholderSecret_isRejectedAtConstruction() {
        // Previously this specific value happened to also fail the length
        // check below, which is why the placeholder-literal bug never
        // manifested here the way it did for TOTP — that was luck, not
        // design, which is why this test (and RequiredSecrets itself) exist.
        assertThatThrownBy(() -> new JwtProperties("${JWT_SECRET}", "issuer", TTL, TTL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tooShortSecret_isRejectedAtConstruction() {
        assertThatThrownBy(() -> new JwtProperties("short", "issuer", TTL, TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void validSecret_isAccepted() {
        String valid = "a".repeat(32);
        assertThatCode(() -> new JwtProperties(valid, "issuer", TTL, TTL)).doesNotThrowAnyException();
    }
}
