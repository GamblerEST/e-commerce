package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Key for PII encrypted at rest.
 *
 * No default, for the same reason as TotpEncryptionProperties: this key is
 * the only thing standing between a stolen database dump and every customer's
 * name, address and phone number. A box silently running with the wrong key —
 * or with a placeholder string that happens to be non-blank — would write
 * ciphertext nobody can ever read back, and the damage is only discovered when
 * someone opens an old order.
 *
 * KEY LOSS IS UNRECOVERABLE. Orders are retained seven years under the
 * Estonian Accounting Act, so losing this key destroys the readable half of
 * seven years of legal records. It must be backed up separately from the
 * database, and a backup of the database alone is NOT a backup of the shop.
 * See README "Encryption at rest".
 */
@ConfigurationProperties(prefix = "app.security.field")
public record FieldEncryptionProperties(String key) {
    public FieldEncryptionProperties {
        RequiredSecrets.requireResolved(key, "app.security.field.key",
                "Generate: openssl rand -base64 32");
    }
}
