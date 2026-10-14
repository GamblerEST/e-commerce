package ee.shop.payment.banklink;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

/**
 * HS256 JSON Web Tokens for Montonio, signed and verified with the store's
 * secret key.
 *
 * Written here instead of using the JWT library behind the shop's own access
 * tokens. That library refuses HMAC keys shorter than 256 bits, and Montonio does
 * not document how long the secret keys it issues are; a payment integration that
 * fails at the first real key is not an option. HS256 is small enough to do
 * exactly: one algorithm, pinned; a constant-time signature comparison; and an
 * expiry that must be present and in the future.
 */
public final class MontonioJwt {

    /** Anything longer is not a Montonio token and not worth decoding. */
    static final int MAX_TOKEN_LENGTH = 8_192;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER =
            ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    public static final class InvalidTokenException extends RuntimeException {
        InvalidTokenException(String reason) {
            super(reason);
        }
    }

    private final ObjectMapper json;
    private final byte[] secret;
    private final Clock clock;

    public MontonioJwt(ObjectMapper json, String secretKey, Clock clock) {
        this.json = json;
        this.secret = secretKey == null ? new byte[0] : secretKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(Map<String, Object> claims) {
        String signingInput = HEADER + "." + ENCODER.encodeToString(json.writeValueAsBytes(claims));
        return signingInput + "." + ENCODER.encodeToString(hmac(signingInput));
    }

    /**
     * @return the token's claims, only if it is a well-formed HS256 token whose
     *         signature this store's secret key produced and which has not expired
     * @throws InvalidTokenException for anything else
     */
    public JsonNode verify(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidTokenException("missing or oversized token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("not a signed token");
        }

        // The algorithm is pinned, never taken from the token. Honouring the header
        // is exactly how "alg": "none" and algorithm-substitution attacks work.
        JsonNode header = decodeJson(parts[0]);
        if (!"HS256".equals(text(header.path("alg")))) {
            throw new InvalidTokenException("unexpected algorithm");
        }

        // Signature before payload: nothing an unverified token says is read.
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expected, decodeBase64(parts[2]))) {
            throw new InvalidTokenException("signature does not match");
        }

        JsonNode claims = decodeJson(parts[1]);
        JsonNode exp = claims.path("exp");
        if (!exp.isNumber() || clock.instant().getEpochSecond() >= exp.asLong()) {
            throw new InvalidTokenException("expired, or no expiry");
        }
        return claims;
    }

    private byte[] hmac(String input) {
        if (secret.length == 0) {
            throw new IllegalStateException("The Montonio secret key is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private JsonNode decodeJson(String part) {
        try {
            JsonNode node = json.readTree(decodeBase64(part));
            if (node == null || !node.isObject()) {
                throw new InvalidTokenException("token segment is not a JSON object");
            }
            return node;
        } catch (InvalidTokenException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidTokenException("token segment is not valid JSON");
        }
    }

    private static byte[] decodeBase64(String part) {
        try {
            return DECODER.decode(part);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("token segment is not base64url");
        }
    }

    static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
