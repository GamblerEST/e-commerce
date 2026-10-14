package ee.shop.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@Tag("security")
class TotpEncryptionPropertiesTest {

    @Test
    void unresolvedPlaceholderKey_isRejectedAtConstruction() {
        // The exact value Spring bound this to in production before
        // totp_encryption_key was wired as a docker secret.
        assertThatThrownBy(() -> new TotpEncryptionProperties("${TOTP_ENCRYPTION_KEY}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankKey_isRejectedAtConstruction() {
        assertThatThrownBy(() -> new TotpEncryptionProperties(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullKey_isRejectedAtConstruction() {
        assertThatThrownBy(() -> new TotpEncryptionProperties(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
