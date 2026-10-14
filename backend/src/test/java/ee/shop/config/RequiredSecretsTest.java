package ee.shop.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the exact failure this class exists to catch: Spring's
 * nested-placeholder config pattern ({@code ${a:${B}}}, used throughout
 * application.yml for "docker secret, else env var, else fail") silently
 * binding the LITERAL unresolved placeholder text instead of failing to
 * resolve, when neither level is actually set. Verified directly against a
 * real Spring context before this class existed — not
 * assumed from documentation.
 */
@Tag("unit")
@Tag("security")
class RequiredSecretsTest {

    @Test
    void aRealValue_passesThrough() {
        assertThatCode(() -> RequiredSecrets.requireResolved("a-real-secret-value", "app.test.key", "hint"))
                .doesNotThrowAnyException();
    }

    @Test
    void nullValue_throws() {
        assertThatThrownBy(() -> RequiredSecrets.requireResolved(null, "app.test.key", "hint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.test.key");
    }

    @Test
    void blankValue_throws() {
        assertThatThrownBy(() -> RequiredSecrets.requireResolved("   ", "app.test.key", "hint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.test.key");
    }

    /**
     * The actual bug, reproduced directly: this is exactly the string Spring
     * bound app.security.totp.key to when both totp_encryption_key and
     * TOTP_ENCRYPTION_KEY were unset — non-null, non-blank, and therefore
     * invisible to a blank-only check.
     */
    @Test
    void unresolvedPlaceholderLiteral_throws() {
        assertThatThrownBy(() -> RequiredSecrets.requireResolved("${TOTP_ENCRYPTION_KEY}", "app.security.totp.key", "hint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.totp.key")
                .hasMessageContaining("unresolved placeholder");
    }

    @Test
    void unresolvedNestedPlaceholderLiteral_alsoThrows() {
        // The outer form, in case some future Spring version's failure mode
        // leaves the OUTER syntax intact instead of resolving down to the
        // inner one the way it did when this was diagnosed.
        assertThatThrownBy(() -> RequiredSecrets.requireResolved(
                "${totp_encryption_key:${TOTP_ENCRYPTION_KEY}}", "app.security.totp.key", "hint"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void errorMessage_includesTheHint() {
        assertThatThrownBy(() -> RequiredSecrets.requireResolved(null, "app.test.key", "Generate: openssl rand -base64 32"))
                .hasMessageContaining("openssl rand -base64 32");
    }
}
