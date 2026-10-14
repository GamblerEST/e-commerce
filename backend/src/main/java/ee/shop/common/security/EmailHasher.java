package ee.shop.common.security;

import ee.shop.config.FieldEncryptionProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * A blind index for email addresses.
 *
 * The problem it solves: an email address is personal data and belongs in
 * ciphertext, but login has to LOOK ACCOUNTS UP by it and a unique index has to
 * enforce one live account per address. AES-GCM uses a random IV, so the same
 * address encrypts to a different string every time — the column cannot be
 * searched or uniquely indexed. That is why users.email stayed in clear text
 * while everything else was encrypted.
 *
 * A blind index is the standard answer: store the encrypted value for reading,
 * and a DETERMINISTIC keyed hash beside it for finding. The hash is what the
 * unique index and every lookup use.
 *
 * HMAC, not a bare SHA-256. An unkeyed hash of an email is trivially reversed —
 * the search space is small enough that an attacker with the dump simply hashes
 * their address list and matches. The key turns that into an attack requiring
 * the key, which is not in the database.
 *
 * The key is DERIVED from the field-encryption key rather than being a separate
 * secret, with a domain-separation label so the two are cryptographically
 * unrelated. One fewer secret to generate, rotate and lose, and losing the
 * field key already makes the encrypted addresses unreadable regardless.
 */
@Component
public class EmailHasher {

    /** Domain separation: this key must not equal the encryption key. */
    private static final String LABEL = "shop:email-blind-index:v1";
    private static final String HMAC = "HmacSHA256";

    private final SecretKeySpec key;

    public EmailHasher(FieldEncryptionProperties props) {
        this.key = new SecretKeySpec(derive(props.key()), HMAC);
    }

    /**
     * @return a stable, URL-safe digest of the address, or null for null input
     *         (an anonymised account has no email and must have no hash either,
     *         or the unique index would block the address being reused).
     */
    public String hash(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        // Normalised first. The column was CITEXT, so "Kadri@Example.EE" and
        // "kadri@example.ee" were the same account; the hash has to preserve
        // that or changing case would create a second account.
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute the email blind index", e);
        }
    }

    private static byte[] derive(String masterKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LABEL.getBytes(StandardCharsets.UTF_8));
            return digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
