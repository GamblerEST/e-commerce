package ee.shop.identity.web;

import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.common.mail.MailService;
import ee.shop.common.security.RateLimitService;
import ee.shop.identity.exception.InvalidOneTimeTokenException;
import ee.shop.identity.model.OneTimeToken;
import ee.shop.common.security.EmailHasher;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.OneTimeTokenService;
import ee.shop.identity.service.TokenService;
import ee.shop.identity.web.dto.ChangePasswordRequest;
import ee.shop.identity.web.dto.ForgotPasswordRequest;
import ee.shop.identity.web.dto.MeResponse;
import ee.shop.identity.web.dto.ResetPasswordRequest;
import ee.shop.identity.web.dto.UpdateProfileRequest;
import ee.shop.identity.web.dto.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

/**
 * Email verification and password reset. Separate from AuthController
 * (register/login/refresh/logout/me) matching the staged build plan's own split
 * between 1.1 and 1.2 — different concern, different lifecycle.
 */
@RestController
@RequestMapping("/api/auth")
public class AccountController {

    /** Short: a reset link is a bearer credential for taking over the
     *  account, unlike a verify-email link. */
    private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

    private final UserRepository users;

    private final EmailHasher emailHasher;
    private final PasswordEncoder passwordEncoder;
    private final OneTimeTokenService oneTimeTokens;
    private final TokenService tokenService;
    private final MailService mail;
    private final AuditService audit;
    private final String frontendUrl;
    private final RateLimitService rateLimit;

    public AccountController(UserRepository users, PasswordEncoder passwordEncoder,
                              OneTimeTokenService oneTimeTokens, TokenService tokenService,
                              MailService mail, AuditService audit,
                              @Value("${app.frontend-url}") String frontendUrl,
                              RateLimitService rateLimit,
                              EmailHasher emailHasher) {
        this.emailHasher = emailHasher;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.oneTimeTokens = oneTimeTokens;
        this.tokenService = tokenService;
        this.mail = mail;
        this.audit = audit;
        this.frontendUrl = frontendUrl;
        this.rateLimit = rateLimit;
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest req, HttpServletRequest http) {
        rateLimit.enforceVerifyEmail(http.getRemoteAddr());

        UUID userId = oneTimeTokens.consume(req.token(), OneTimeToken.Purpose.EMAIL_VERIFY)
                .orElseThrow(() -> new InvalidOneTimeTokenException("verification"));

        User user = users.findById(userId)
                .orElseThrow(() -> new InvalidOneTimeTokenException("verification"));

        user.markEmailVerified();
        users.save(user);

        audit.record("user.email_verified", user.getId(), null, http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    /**
     * MUST be email-enumeration-safe: identical response whether or not the
     * address belongs to an account. See AccountControllerTest for the test
     * that actually attacks this.
     */
    /**
     * Sends a fresh verification link to an account that has not verified its
     * address yet, for when the first email never arrived. Same 202 for every
     * address, so it cannot be used to find out which accounts exist. Shares the
     * forgot-password budget because both send mail on an unauthenticated request.
     */
    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest http) {
        rateLimit.enforceForgotPassword(http.getRemoteAddr());

        users.findActiveByEmailHash(emailHasher.hash(req.email()))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> {
                    String token = oneTimeTokens.issue(user, OneTimeToken.Purpose.EMAIL_VERIFY,
                            AuthController.EMAIL_VERIFY_TTL);
                    String link = frontendUrl + "/verify-email?token=" + token;
                    mail.send(user.getEmail(), "Verify your email",
                            "Verify your email address: " + link + "\n\nThis link expires in 24 hours.",
                            "verify-email");
                });
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest http) {
        rateLimit.enforceForgotPassword(http.getRemoteAddr());

        users.findActiveByEmailHash(emailHasher.hash(req.email())).ifPresent(user -> {
            String token = oneTimeTokens.issue(user, OneTimeToken.Purpose.PASSWORD_RESET, PASSWORD_RESET_TTL);
            String link = frontendUrl + "/reset-password?token=" + token;
            mail.send(user.getEmail(), "Reset your password",
                    "Reset your password: " + link + "\n\nThis link expires in 1 hour. " +
                            "If you didn't request this, ignore this email.",
                    "password-reset");

            // Audit only on the found branch, and only the userId — never the
            // submitted email (rule: never log PII). Recording nothing at
            // all for unknown emails would itself be an observable difference
            // an attacker could infer from response TIMING; recording nothing
            // in the RESPONSE (both branches return the same 202) is what
            // actually matters and is preserved here regardless.
            audit.record("auth.password.forgot_requested", user.getId(), null,
                    http.getRemoteAddr(), http.getHeader("User-Agent"));
        });
        // No else — same 202 either way, no timing-sensitive work on the
        // "unknown email" branch beyond the lookup itself.
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest req, HttpServletRequest http) {
        rateLimit.enforcePasswordReset(http.getRemoteAddr());

        UUID userId = oneTimeTokens.consume(req.token(), OneTimeToken.Purpose.PASSWORD_RESET)
                .orElseThrow(() -> new InvalidOneTimeTokenException("reset"));

        User user = users.findById(userId)
                .orElseThrow(() -> new InvalidOneTimeTokenException("reset"));

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        users.save(user);

        // A password reset means either the owner regained control or an
        // attacker who had the OLD password no longer matters — either way,
        // every existing session should die and force a fresh login with the
        // new credential.
        tokenService.revokeAllSessions(user.getId(), "password_reset");

        audit.record("auth.password.reset_completed", user.getId(), null,
                http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    /**
     * The authenticated counterpart to /password/reset: a logged-in user
     * changing their password on purpose, proven by supplying the CURRENT
     * one (an active access token alone isn't proof — see the same
     * reasoning on TotpController.disable). Deliberately NOT in
     * SecurityConfig's permitAll list, unlike /forgot and /reset.
     */
    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                Authentication authentication, HttpServletRequest http) {
        User user = (User) authentication.getPrincipal();

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw AppException.unauthorized("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        users.save(user);

        // Same rule as a token-based reset: a password change kills every
        // existing session, including this one, and forces a fresh login.
        tokenService.revokeAllSessions(user.getId(), "password_change");

        audit.record("auth.password.changed", user.getId(), null,
                http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    @PostMapping("/profile")
    public MeResponse updateProfile(@Valid @RequestBody UpdateProfileRequest req, Authentication authentication) {
        User user = (User) authentication.getPrincipal();

        user.setDisplayName(req.displayName());
        users.save(user);

        String role = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), role, user.is2faEnabled());
    }
}
