package ee.shop.payment.banklink;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A payment result is only as trustworthy as the check on its signature, so this
 * attacks that check directly with the classic token forgeries.
 */
@Tag("unit")
@Tag("security")
class MontonioJwtTest {

    private static final String SECRET = "short-secret"; // well under 256 bits, deliberately
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final MontonioJwt jwt = new MontonioJwt(JsonMapper.builder().build(), SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Map<String, Object> claims(long exp) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("uuid", "7f0c3b6e-2a6c-4d0f-9c55-1b1d0f6c2a11");
        c.put("paymentStatus", "PAID");
        c.put("grandTotal", 12.90);
        c.put("exp", exp);
        return c;
    }

    private static String handMade(String headerJson, String payloadJson, String secret) throws Exception {
        String input = B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private static long later() {
        return NOW.getEpochSecond() + 600;
    }

    @Test
    void aTokenSignedWithTheStoreKeyVerifiesEvenWithAShortKey() {
        JsonNode verified = jwt.verify(jwt.sign(claims(later())));

        assertThat(verified.path("paymentStatus").asText()).isEqualTo("PAID");
        assertThat(verified.path("grandTotal").asText()).isEqualTo("12.9");
    }

    @Test
    void theNoneAlgorithmIsRefused() {
        String header = B64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(("{\"paymentStatus\":\"PAID\",\"exp\":" + later() + "}").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> jwt.verify(header + "." + payload + "."))
                .isInstanceOf(MontonioJwt.InvalidTokenException.class);
    }

    /** Signed with the right key, but claiming another algorithm: the header is never trusted. */
    @Test
    void aTokenClaimingAnotherAlgorithmIsRefused() throws Exception {
        String token = handMade("{\"alg\":\"HS512\",\"typ\":\"JWT\"}", "{\"exp\":" + later() + "}", SECRET);

        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(MontonioJwt.InvalidTokenException.class);
    }

    @Test
    void aTamperedPayloadIsRefused() {
        String[] parts = jwt.sign(claims(later())).split("\\.");
        String forgedPayload = B64.encodeToString(
                ("{\"paymentStatus\":\"PAID\",\"grandTotal\":0.01,\"exp\":" + later() + "}").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> jwt.verify(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(MontonioJwt.InvalidTokenException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        String token = handMade("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", "{\"exp\":" + later() + "}", "someone-elses-key");

        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(MontonioJwt.InvalidTokenException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {
        assertThatThrownBy(() -> jwt.verify(jwt.sign(claims(NOW.getEpochSecond() - 1))))
                .isInstanceOf(MontonioJwt.InvalidTokenException.class);
    }

    /** A token that never expires could be replayed forever. */
    @Test
    void aTokenWithoutAnExpiryIsRefused() {
        Map<String, Object> noExpiry = claims(later());
        noExpiry.remove("exp");

        assertThatThrownBy(() -> jwt.verify(jwt.sign(noExpiry))).isInstanceOf(MontonioJwt.InvalidTokenException.class);
    }

    @Test
    void malformedInputIsRefusedWithoutThrowingAnythingElse() {
        for (String garbage : new String[] {null, "", "not-a-token", "a.b", "a.b.c.d", "!!!.###.$$$",
                "x".repeat(MontonioJwt.MAX_TOKEN_LENGTH + 1)}) {
            assertThatThrownBy(() -> jwt.verify(garbage))
                    .as("input: %s", garbage == null ? "null" : garbage.substring(0, Math.min(20, garbage.length())))
                    .isInstanceOf(MontonioJwt.InvalidTokenException.class);
        }
    }
}
