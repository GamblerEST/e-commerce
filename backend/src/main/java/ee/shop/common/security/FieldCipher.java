package ee.shop.common.security;

import ee.shop.config.FieldEncryptionProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM for personal data stored at rest.
 *
 * GCM rather than CBC because it is authenticated: a ciphertext altered in
 * the database fails to decrypt rather than silently yielding different
 * plaintext. For an address or a phone number that matters — a corrupted
 * value that still "decrypts" is worse than one that loudly does not.
 *
 * Ciphertext carries a version PREFIX ("enc:v1:"). Two reasons:
 *
 *  1. It makes rows that are still plaintext trivially findable in SQL
 *     (`WHERE billing_city NOT LIKE 'enc:v1:%'`), so "did the migration
 *     actually cover everything" is a query rather than a guess.
 *  2. It leaves room to rotate the scheme later without having to guess
 *     whether an opaque base64 blob is v1 or v2.
 *
 * The IV is random per encryption and prepended to the ciphertext, which is
 * the standard AES-GCM storage layout. That randomness is also why these
 * columns can never be searched or uniquely indexed: the same input encrypts
 * to a different value every time. Anything that must be looked up by value
 * needs a separate blind index, not this.
 */
@Component
public class FieldCipher {

    public static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public FieldCipher(FieldEncryptionProperties props) {
        // SHA-256 of the configured string, so any operator-supplied key
        // length yields a valid 32-byte AES key — same simplification
        // TotpSecretCipher and JwtProperties already make.
        this.key = new SecretKeySpec(sha256(props.key()), "AES");
    }

    /** Null and empty pass through unchanged: an absent value is not a secret. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (plaintext.startsWith(PREFIX)) {
            // Already encrypted. Encrypting twice would still decrypt back to
            // a ciphertext string rather than the original value.
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Never include the plaintext in the message — this exception is
            // logged, and logging PII is exactly what encrypting it prevents.
            throw new IllegalStateException("Failed to encrypt a personal-data field", e);
        }
    }

    /**
     * Unprefixed input is returned as-is, so rows written before this feature
     * existed keep working. That tolerance is deliberate and bounded: it
     * applies only to values that were never encrypted, and the prefix makes
     * those rows greppable so the gap cannot hide.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Authentication failure means the wrong key or a tampered row.
            // Failing loudly is the point: returning the raw ciphertext would
            // put an unreadable blob on an invoice and call it an address.
            throw new IllegalStateException(
                    "Failed to decrypt a personal-data field — wrong app.security.field.key, "
                            + "or the stored value has been altered", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
