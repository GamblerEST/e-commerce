package ee.shop.identity.service;

import ee.shop.config.TotpEncryptionProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests, no Spring context — this is the ONLY thing standing
 * between a database dump and every user's TOTP secret (V1 migration
 * comment). Security-critical logic needs a test that actually
 * attacks it, not just a happy path, so this includes deliberate ciphertext
 * tampering and a wrong-key attempt, not just an encrypt/decrypt roundtrip.
 */
@Tag("unit")
@Tag("security")
class TotpSecretCipherTest {

    private final TotpSecretCipher cipher = new TotpSecretCipher(new TotpEncryptionProperties("test-key-1"));

    @Test
    void encryptThenDecrypt_returnsTheOriginalSecret() {
        String secret = "JBSWY3DPEHPK3PXP"; // a plausible base32 TOTP secret

        String encrypted = cipher.encrypt(secret);
        String decrypted = cipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(secret);
    }

    @Test
    void encryptingTheSameSecretTwice_producesDifferentCiphertext() {
        // The IV is random per call — identical ciphertext for identical
        // plaintext would leak that two users share a secret.
        String secret = "JBSWY3DPEHPK3PXP";

        String first = cipher.encrypt(secret);
        String second = cipher.encrypt(secret);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(secret);
        assertThat(cipher.decrypt(second)).isEqualTo(secret);
    }

    /**
     * A single flipped byte anywhere in the stored ciphertext must be
     * rejected, not silently decrypted to garbage — this is what GCM's
     * authentication tag is FOR. If this test ever passes with a corrupted
     * value decrypting "successfully", the cipher mode has been broken.
     */
    @Test
    void tamperedCiphertext_failsToDecrypt() {
        String encrypted = cipher.encrypt("JBSWY3DPEHPK3PXP");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01; // flip one bit near the end (inside the GCM tag)
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKey_failsToDecrypt() {
        TotpSecretCipher otherCipher = new TotpSecretCipher(new TotpEncryptionProperties("a-completely-different-key"));
        String encrypted = cipher.encrypt("JBSWY3DPEHPK3PXP");

        assertThatThrownBy(() -> otherCipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void garbageInput_failsToDecryptRatherThanThrowingAnUncheckedException() {
        assertThatThrownBy(() -> cipher.decrypt("not-valid-base64-or-ciphertext"))
                .isInstanceOf(IllegalStateException.class);
    }
}
