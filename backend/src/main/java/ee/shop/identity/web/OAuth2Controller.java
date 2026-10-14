package ee.shop.identity.web;

import ee.shop.cart.service.CartService;
import ee.shop.cart.web.GuestCartCookieFactory;
import ee.shop.common.audit.AuditService;
import ee.shop.common.security.TokenCrypto;
import ee.shop.config.GoogleOAuthProperties;
import ee.shop.identity.exception.OAuth2NotConfiguredException;
import ee.shop.identity.exception.OAuthStateInvalidException;
import ee.shop.identity.model.User;
import ee.shop.identity.service.GoogleOAuthClient;
import ee.shop.identity.service.OAuthAccountService;
import ee.shop.identity.service.TokenService;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.AuthorizeResponse;
import ee.shop.identity.web.dto.OAuthCallbackRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth/oauth2/google")
public class OAuth2Controller {

    private static final String STATE_KEY_PREFIX = "auth:oauth2:google:state:";

    /** Long enough for a human to actually complete the Google consent
     *  screen, short enough that a captured/replayed state is useless soon. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final GoogleOAuthProperties props;
    private final GoogleOAuthClient client;
    private final OAuthAccountService accountService;
    private final TokenService tokenService;
    private final RefreshCookieFactory cookies;
    private final StringRedisTemplate redis;
    private final AuditService audit;
    private final CartService cartService;
    private final GuestCartCookieFactory guestCartCookies;

    public OAuth2Controller(GoogleOAuthProperties props, GoogleOAuthClient client,
                             OAuthAccountService accountService, TokenService tokenService,
                             RefreshCookieFactory cookies, StringRedisTemplate redis,
                             AuditService audit, CartService cartService,
                             GuestCartCookieFactory guestCartCookies) {
        this.props = props;
        this.client = client;
        this.accountService = accountService;
        this.tokenService = tokenService;
        this.cookies = cookies;
        this.redis = redis;
        this.audit = audit;
        this.cartService = cartService;
        this.guestCartCookies = guestCartCookies;
    }

    @GetMapping("/authorize")
    public AuthorizeResponse authorize() {
        if (!props.isEnabled()) {
            throw new OAuth2NotConfiguredException();
        }

        String state = TokenCrypto.randomToken();
        redis.opsForValue().set(STATE_KEY_PREFIX + state, "1", STATE_TTL);
        return new AuthorizeResponse(client.buildAuthorizationUrl(state));
    }

    @PostMapping("/callback")
    public AuthResponse callback(@Valid @RequestBody OAuthCallbackRequest req,
                                 @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestCartToken,
                                 HttpServletRequest http, HttpServletResponse response) {
        if (!props.isEnabled()) {
            throw new OAuth2NotConfiguredException();
        }

        // Atomic get-and-delete: single-use, and immune to the same
        // check-then-act race every other single-use token in this codebase
        // avoids (see RefreshTokenRepository.claim's javadoc).
        String seen = redis.opsForValue().getAndDelete(STATE_KEY_PREFIX + req.state());
        if (seen == null) {
            throw new OAuthStateInvalidException();
        }

        GoogleOAuthClient.GoogleUserInfo profile = client.exchangeCodeForUserInfo(req.code());

        String ip = http.getRemoteAddr();
        String ua = http.getHeader("User-Agent");
        User user = accountService.linkOrCreate("google", profile);
        audit.record("oauth.google.login", user.getId(), null, ip, ua);

        TokenService.TokenPair pair = tokenService.issueNewFamily(user, ua, ip);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(pair.refreshToken()).toString());
        if (guestCartToken != null) {
            cartService.mergeGuestIntoUser(guestCartToken, user.getId());
            response.addHeader(HttpHeaders.SET_COOKIE, guestCartCookies.clear().toString());
        }
        return AuthResponse.from(pair);
    }
}
