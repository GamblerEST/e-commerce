package ee.shop.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Shared by every single-use-token flow (refresh tokens, one-time tokens):
 * mint 256 bits of CSPRNG output for the raw token, store only its SHA-256
 * hash. A stolen database dump therefore yields no usable tokens, and SHA-256
 * (not Argon2) is right here — the token is already 256 bits of CSPRNG
 * output, so there is nothing to brute-force, and lookups need to be fast.
 *
 * Pulled out once two services needed identical logic (TokenService,
 * OneTimeTokenService) — token generation is exactly the kind of
 * security-critical code that must exist in one place, not be re-derived
 * per call site.
 */
public final class TokenCrypto {

    /**
     * SecureRandom, not Random. java.util.Random is a linear congruential
     * generator: observe a couple of outputs and you can predict every future
     * one. Predictable tokens = anyone can mint a session or reset a password.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenCrypto() {}

    /** 256 bits of CSPRNG output. Not a UUID — UUIDv4 gives only 122 bits. */
    public static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256")
                                .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
