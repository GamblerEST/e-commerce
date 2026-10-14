package ee.shop.config;

/**
 * Shared validation for config values that have deliberately no hardcoded
 * default (DECISIONS.md §9 — a missing value must fail the app's boot, not
 * run it silently against the wrong thing).
 *
 * The blank check alone is not enough. Spring's nested-placeholder syntax
 * (this codebase's own pattern for "docker secret file, else env var, else
 * fail": {@code ${a:${B}}}) does NOT always throw when NEITHER level
 * resolves — confirmed directly, not assumed: with both
 * {@code totp_encryption_key} and {@code TOTP_ENCRYPTION_KEY} unset,
 * {@code app.security.totp.key} bound to the LITERAL STRING
 * {@code "${TOTP_ENCRYPTION_KEY}"} instead of failing to resolve. That
 * string is non-null and non-blank, so a blank-only check passes it straight
 * through — and the app boots successfully using a value anyone reading this
 * source code can reproduce. TotpEncryptionProperties shipped with exactly
 * this hole; JwtProperties happened to survive only because it also checks
 * length, not because anyone intended that check to double as this one.
 */
final class RequiredSecrets {

    private RequiredSecrets() {}

    static void requireResolved(String value, String propertyPath, String generateHint) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyPath + " must be set. " + generateHint);
        }
        if (value.contains("${")) {
            throw new IllegalStateException(
                propertyPath + " resolved to \"" + value + "\", which still contains an unresolved " +
                "placeholder rather than a real value — the docker secret file or env var behind it " +
                "is not actually set. " + generateHint);
        }
    }
}
