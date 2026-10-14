package ee.shop.common.security;

import ee.shop.common.error.AppException;
import ee.shop.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Token-bucket per IP+route, backed by Redis (see
 * RateLimitConfig) so the limit is shared across every app instance — an
 * in-process limiter resets per container and is useless behind a load
 * balancer with more than one replica.
 *
 * Routes reachable by both guests AND authenticated users additionally get
 * a SEPARATE per-user bucket (enforceIpAndUser) — IP-only limiting alone is
 * bypassed by an attacker (or a compromised account) rotating source IPs;
 * a per-user bucket catches that regardless of which IP a request arrives
 * from. Both buckets share the same configured limit and either one being
 * exhausted rejects the request.
 */
@Service
public class RateLimitService {

    private final ProxyManager<String> proxyManager;
    private final RateLimitProperties props;

    public RateLimitService(ProxyManager<String> proxyManager, RateLimitProperties props) {
        this.proxyManager = proxyManager;
        this.props = props;
    }

    /** Public, unauthenticated, and sends mail — an open relay without this. */
    public void enforceContact(String ip)        { enforce("contact", ip, props.contact()); }
    public void enforceAddressLookup(String ip) { enforce("address_lookup", ip, props.addressLookup()); }
    public void enforceRegister(String ip)       { enforce("register", ip, props.register()); }
    public void enforceLogin(String ip)          { enforce("login", ip, props.login()); }
    public void enforceRefresh(String ip)        { enforce("refresh", ip, props.refresh()); }
    public void enforceForgotPassword(String ip) { enforce("forgot_password", ip, props.forgotPassword()); }

    /**
     * Both consume a client-supplied one-time token before the caller has
     * any authenticated identity — IP is the only dimension available, same
     * shape as forgot-password. Defends against brute-forcing/guessing a
     * verification or reset token, not just against issuing too many of them.
     */
    public void enforceVerifyEmail(String ip)   { enforce("verify_email", ip, props.verifyEmail()); }
    public void enforcePasswordReset(String ip) { enforce("password_reset", ip, props.passwordReset()); }

    /** userId is null for a guest — falls back to IP-only in that case. */
    public void enforceCheckout(String ip, UUID userId)      { enforceIpAndUser("checkout", ip, userId, props.checkout()); }
    public void enforceCartMutation(String ip, UUID userId)  { enforceIpAndUser("cart", ip, userId, props.cartMutation()); }
    public void enforceProductSearch(String ip, UUID userId) { enforceIpAndUser("product_search", ip, userId, props.productSearch()); }

    private void enforceIpAndUser(String route, String ip, UUID userId, RateLimitProperties.Limit limit) {
        enforce(route, "ip:" + ip, limit);
        if (userId != null) {
            enforce(route, "user:" + userId, limit);
        }
    }

    private void enforce(String route, String subject, RateLimitProperties.Limit limit) {
        String key = route + ":" + subject;
        BucketProxy bucket = proxyManager.builder().build(key, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period())
                        .build())
                .build());

        if (!bucket.tryConsume(1)) {
            throw AppException.tooManyRequests("Too many requests. Please try again later.");
        }
    }
}
