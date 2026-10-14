package ee.shop.identity.web;

import ee.shop.config.JwtProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Shared between AuthController and OAuth2Controller — both issue the same
 * refresh_token cookie after minting a token pair, and the attributes
 * (HttpOnly, Secure, SameSite=Strict, Path=/api/auth) must stay identical
 * across every issuer or one code path quietly drifts from the security
 * posture documented in DECISIONS.md §11.
 */
@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "refresh_token";

    /**
     * Path is the parent /api/auth, not the narrower /api/auth/refresh —
     * logout (and the OAuth2 callback) also need to read/clear this same
     * cookie, and a browser only attaches a cookie to requests whose path it
     * is an ancestor of. Scoping to /api/auth still keeps the cookie away
     * from every non-auth endpoint (cart, catalog, orders...).
     */
    private static final String PATH = "/api/auth";

    private final JwtProperties jwtProps;

    public RefreshCookieFactory(JwtProperties jwtProps) {
        this.jwtProps = jwtProps;
    }

    public ResponseCookie issue(String rawToken) {
        return ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(jwtProps.refreshTtl())
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
}
