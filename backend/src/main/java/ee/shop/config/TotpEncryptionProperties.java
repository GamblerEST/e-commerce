package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No default, like JwtProperties.secret — this key is the ONLY thing
 * standing between a database dump and every user's TOTP secret (V1
 * migration: "TOTP secret, encrypted at rest by the application, never
 * plaintext"). A misconfigured box silently storing secrets under the wrong
 * key, or unencrypted, is exactly the "fail loud, not silent" failure mode
 * DECISIONS.md §9 warns about.
 */
@ConfigurationProperties(prefix = "app.security.totp")
public record TotpEncryptionProperties(String key) {
    public TotpEncryptionProperties {
        RequiredSecrets.requireResolved(key, "app.security.totp.key", "Generate: openssl rand -base64 32");
    }
}
