package ee.shop.cart.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * A guest cart is opaque-cookie-id -> JSON list of {productId, quantity} in
 * Redis, TTL-backed — no Postgres row until it's merged
 * into a real account's cart on login/register (CartService.mergeGuestIntoUser).
 * Same reasoning as an abandoned checkout: nobody should have to pay to store
 * a browsing session's cart forever.
 */
@Service
public class GuestCartStore {

    private static final String KEY_PREFIX = "cart:guest:";

    /** Generous but finite — an abandoned guest cart eventually falls off Redis
     *  on its own rather than accumulating forever. */
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public GuestCartStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public List<GuestCartItem> get(String token) {
        String raw = redis.opsForValue().get(KEY_PREFIX + token);
        if (raw == null) {
            return List.of();
        }
        return json.readValue(raw, json.getTypeFactory().constructCollectionType(List.class, GuestCartItem.class));
    }

    public void save(String token, List<GuestCartItem> items) {
        if (items.isEmpty()) {
            delete(token);
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + token, json.writeValueAsString(items), TTL);
    }

    public void delete(String token) {
        redis.delete(KEY_PREFIX + token);
    }
}
