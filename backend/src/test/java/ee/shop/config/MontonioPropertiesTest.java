package ee.shop.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class MontonioPropertiesTest {

    @Test
    void switchedOffNeedsNothing() {
        assertThatCode(() -> new MontonioProperties(false, null, null, null)).doesNotThrowAnyException();
    }

    /**
     * Switched on with a missing value, the application must refuse to start rather
     * than offer a payment method that fails for every customer.
     */
    @Test
    void switchedOnWithoutASecretKeyRefusesToStart() {
        assertThatThrownBy(() -> new MontonioProperties(true, "https://sandbox-stargate.montonio.com/api", "access", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.montonio.secret-key");
    }

    @Test
    void switchedOnWithoutAnApiUrlRefusesToStart() {
        assertThatThrownBy(() -> new MontonioProperties(true, null, "access", "secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.montonio.api-url");
    }

    /** An unresolved ${...} placeholder is a missing secret file, not a value. */
    @Test
    void anUnresolvedPlaceholderIsNotAKey() {
        assertThatThrownBy(() -> new MontonioProperties(true, "https://x", "access", "${montonio_secret_key}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
