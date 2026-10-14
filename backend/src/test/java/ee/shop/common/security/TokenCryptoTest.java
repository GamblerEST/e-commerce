package ee.shop.common.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the crypto primitive shared by every single-use-token
 * flow (refresh tokens, one-time tokens). A weak or colliding randomToken()
 * here compromises both, silently.
 */
@Tag("unit")
@Tag("security")
class TokenCryptoTest {

    @Test
    void randomToken_isNotPredictableAcrossManyCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(TokenCrypto.randomToken()))
                    .as("token #%d collided with a previous one", i)
                    .isTrue();
        }
    }

    @Test
    void randomToken_encodes256BitsOfOutput() {
        // 32 raw bytes, base64url-without-padding: 32 * 8 / 6 rounded up = 43 chars.
        String token = TokenCrypto.randomToken();
        assertThat(token).hasSize(43);
        assertThat(token).doesNotContain("=", "+", "/"); // URL-safe alphabet, no padding
    }

    @Test
    void sha256_isDeterministic() {
        byte[] first = TokenCrypto.sha256("same-input");
        byte[] second = TokenCrypto.sha256("same-input");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void sha256_differsForDifferentInput() {
        byte[] a = TokenCrypto.sha256("input-a");
        byte[] b = TokenCrypto.sha256("input-b");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void sha256_matchesKnownTestVector() {
        // NIST test vector for SHA-256("abc"), verified against `sha256sum`.
        byte[] digest = TokenCrypto.sha256("abc");
        String hex = java.util.HexFormat.of().formatHex(digest);
        assertThat(hex).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
