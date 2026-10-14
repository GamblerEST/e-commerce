package ee.shop.identity.web;

import ee.shop.cart.service.CartService;
import ee.shop.cart.web.GuestCartCookieFactory;
import ee.shop.common.audit.AuditService;
import ee.shop.common.mail.MailService;
import ee.shop.common.security.RateLimitService;
import ee.shop.identity.exception.EmailAlreadyRegisteredException;
import ee.shop.identity.exception.InvalidCredentialsException;
import ee.shop.identity.exception.InvalidRefreshTokenException;
import ee.shop.identity.exception.TotpCodeRequiredException;
import ee.shop.identity.model.OneTimeToken;
import ee.shop.common.security.EmailHasher;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.AccessTokenDenylistService;
import ee.shop.identity.service.OneTimeTokenService;
import ee.shop.identity.service.RecaptchaService;
import ee.shop.identity.service.RecoveryCodeService;
import ee.shop.identity.service.TokenService;
import ee.shop.identity.service.TotpService;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.LoginRequest;
import ee.shop.identity.web.dto.MeResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Long-lived: verifying an email address is not a sensitive action, so
     *  there's no reason to make a customer race a short clock for it. */
    static final Duration EMAIL_VERIFY_TTL = Duration.ofHours(24);

    private final UserRepository users;

    private final EmailHasher emailHasher;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService audit;
    private final RecaptchaService recaptcha;
    private final AccessTokenDenylistService denylist;
    private final RefreshCookieFactory cookies;
    private final OneTimeTokenService oneTimeTokens;
    private final MailService mail;
    private final String frontendUrl;
    private final TotpService totpService;
    private final RecoveryCodeService recoveryCodes;
    private final RateLimitService rateLimit;
    private final CartService cartService;
    private final GuestCartCookieFactory guestCartCookies;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder,
                           TokenService tokenService, AuditService audit,
                           RecaptchaService recaptcha, AccessTokenDenylistService denylist,
                           RefreshCookieFactory cookies, OneTimeTokenService oneTimeTokens,
                           MailService mail, @Value("${app.frontend-url}") String frontendUrl,
                           TotpService totpService, RecoveryCodeService recoveryCodes,
                           RateLimitService rateLimit, CartService cartService,
                           GuestCartCookieFactory guestCartCookies,
                              EmailHasher emailHasher) {
        this.emailHasher = emailHasher;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.audit = audit;
        this.recaptcha = recaptcha;
        this.denylist = denylist;
        this.cookies = cookies;
        this.oneTimeTokens = oneTimeTokens;
        this.mail = mail;
        this.frontendUrl = frontendUrl;
        this.totpService = totpService;
        this.recoveryCodes = recoveryCodes;
        this.rateLimit = rateLimit;
        this.cartService = cartService;
        this.guestCartCookies = guestCartCookies;
    }

    /**
     * Shared by register/login/OAuth2Controller's callback: a guest's cart
     * (Redis, GuestCartCookieFactory's cookie) is the closest thing they had
     * to a session, so every path that turns "guest" into "logged in" folds
     * it into the account's real cart and clears the cookie — otherwise the
     * items they picked out before creating an account just vanish.
     */
    private void mergeGuestCart(String guestCartToken, User user, HttpServletResponse response) {
        if (guestCartToken == null) {
            return;
        }
        cartService.mergeGuestIntoUser(guestCartToken, user.getId());
        response.addHeader(HttpHeaders.SET_COOKIE, guestCartCookies.clear().toString());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http,
                                  @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestCartToken,
                                  HttpServletResponse response) {
        String ip = http.getRemoteAddr();
        String ua = http.getHeader("User-Agent");

        rateLimit.enforceRegister(ip);

        // Scoped to registration only — login abuse is rate limiting's job,
        // not reCAPTCHA's.
        recaptcha.verify(req.recaptchaToken(), ip);

        // A UX-only pre-check. The real guard against two concurrent
        // registrations with the same address is the partial unique index on
        // the blind index, users_email_hash_unique — caught below.
        if (users.existsByEmailHash(emailHasher.hash(req.email()))) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User(req.email(), emailHasher.hash(req.email()),
                passwordEncoder.encode(req.password()), req.displayName());

        try {
            user = users.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException();
        }

        audit.record("user.registered", user.getId(), null, ip, ua);
        sendVerificationEmail(user);

        TokenService.TokenPair pair = tokenService.issueNewFamily(user, ua, ip);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(pair.refreshToken()).toString());
        mergeGuestCart(guestCartToken, user, response);
        return AuthResponse.from(pair);
    }

    /**
     * Best-effort: MailService itself swallows send failures (see its
     * javadoc), so a downed SMTP relay never fails registration. The account
     * exists either way; the user can resend verification later.
     */
    private void sendVerificationEmail(User user) {
        String token = oneTimeTokens.issue(user, OneTimeToken.Purpose.EMAIL_VERIFY, EMAIL_VERIFY_TTL);
        String link = frontendUrl + "/verify-email?token=" + token;
        mail.send(user.getEmail(), "Verify your email",
                "Welcome! Verify your email address: " + link + "\n\nThis link expires in 24 hours.",
                "verify-email");
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http,
                               @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestCartToken,
                               HttpServletResponse response) {
        String ip = http.getRemoteAddr();
        String ua = http.getHeader("User-Agent");

        rateLimit.enforceLogin(ip);

        User user = users.findActiveByEmailHash(emailHasher.hash(req.email())).orElse(null);

        if (user == null || !user.isActive() || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            audit.record("auth.login.failure", user != null ? user.getId() : null, null, ip, ua);
            throw new InvalidCredentialsException();
        }

        // Credentials are already confirmed correct at this point, so
        // checking 2FA here can never leak whether an EMAIL is registered —
        // only whether an already-authenticated-by-password account also
        // needs a second factor.
        if (user.is2faEnabled()) {
            boolean validTotp = totpService.verify(user.getTotpSecretEnc(), req.totpCode());
            boolean validRecovery = !validTotp && recoveryCodes.consume(user.getId(), req.totpCode());
            if (!validTotp && !validRecovery) {
                audit.record("auth.login.totp_failure", user.getId(), null, ip, ua);
                throw new TotpCodeRequiredException();
            }
        } else if (user.getRole() == User.Role.ADMIN) {
            // Not enforced as a hard block yet — see OAuthAccountService-era
            // discussion: there is no admin-creation/promotion flow to test a
            // lockout against, and blocking login entirely here with no
            // recovery path would risk a permanent lockout. The real
            // enforcement point is future admin-only endpoints checking
            // is2faEnabled() before allowing sensitive actions (planned for
            // the admin screens). This WARN is the audit trail for that gap.
            audit.record("auth.admin_login_without_2fa", user.getId(), null, ip, ua);
        }

        audit.record("auth.login.success", user.getId(), null, ip, ua);

        TokenService.TokenPair pair = tokenService.issueNewFamily(user, ua, ip);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(pair.refreshToken()).toString());
        mergeGuestCart(guestCartToken, user, response);
        return AuthResponse.from(pair);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshToken,
                                 HttpServletRequest http, HttpServletResponse response) {
        String ip = http.getRemoteAddr();
        String ua = http.getHeader("User-Agent");

        rateLimit.enforceRefresh(ip);

        if (refreshToken == null) {
            throw new InvalidRefreshTokenException();
        }

        TokenService.TokenPair pair = tokenService.rotateForToken(refreshToken, ua, ip);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(pair.refreshToken()).toString());
        return AuthResponse.from(pair);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshToken,
                        Authentication authentication, HttpServletRequest http, HttpServletResponse response) {
        String ip = http.getRemoteAddr();
        String ua = http.getHeader("User-Agent");

        if (refreshToken != null) {
            tokenService.revokeFamilyOf(refreshToken, "user_logout");
        }

        // Denylist the CURRENT access token's jti so this logout is immediate
        // rather than waiting out the token's remaining TTL. See
        // AccessTokenDenylistService for why this stays narrow to this one case.
        bearerToken(http)
                .flatMap(tokenService::verifyAccessToken)
                .ifPresent(claims -> denylist.denylist(claims.jti(), claims.expiresAt()));

        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            audit.record("auth.logout", user.getId(), null, ip, ua);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), role, user.is2faEnabled());
    }

    private Optional<String> bearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return Optional.of(header.substring("Bearer ".length()));
        }
        return Optional.empty();
    }
}
