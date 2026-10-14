package ee.shop.identity.service;

import ee.shop.config.TotpEncryptionProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM at rest for TOTP secrets — see TotpEncryptionProperties for why
 * this key has no default. The configured key string is hashed with SHA-256
 * to derive a fixed 32-byte AES key regardless of the operator's input
 * length, the same simplification JwtProperties makes for its HMAC key.
 */
@Service
public class TotpSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public TotpSecretCipher(TotpEncryptionProperties props) {
        this.key = new SecretKeySpec(sha256(props.key()), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // IV need not be secret, only unique per encryption — prepending
            // it to the ciphertext is the standard AES-GCM storage pattern.
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt TOTP secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt TOTP secret", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
