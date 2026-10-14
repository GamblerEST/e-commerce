package ee.shop.cart.web;

import ee.shop.common.security.TokenCrypto;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Path is "/api", not just "/api/cart" — AuthController/OAuth2Controller
 * also need to read this cookie (to merge a guest cart into the account on
 * login/register), and a browser only attaches a cookie to requests whose
 * path it is an ancestor of. Same reasoning as RefreshCookieFactory's PATH.
 */
@Component
public class GuestCartCookieFactory {

    public static final String COOKIE_NAME = "guest_cart_id";

    private static final String PATH = "/api";

    /** Generous: an abandoned guest cart falling off Redis (GuestCartStore's
     *  own, shorter TTL) matters more than this cookie's own expiry. */
    private static final Duration TTL = Duration.ofDays(30);

    public ResponseCookie issue(String token) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(TTL)
                .build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(0)
                .build();
    }

    public String newToken() {
        return TokenCrypto.randomToken();
    }
}
