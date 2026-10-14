package ee.shop.identity.web;

import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.identity.exception.InvalidTotpCodeException;
import ee.shop.identity.exception.TotpSetupRequiredException;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.RecoveryCodeService;
import ee.shop.identity.service.TotpService;
import ee.shop.identity.web.dto.TotpDisableRequest;
import ee.shop.identity.web.dto.TotpSetupResponse;
import ee.shop.identity.web.dto.TotpVerifyRequest;
import ee.shop.identity.web.dto.TotpVerifyResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Requires authentication (not in SecurityConfig's permitAll list, so it
 * falls under anyRequest().authenticated()) — TOTP setup is something an
 * already-logged-in user does for their OWN account, never a public endpoint.
 */
@RestController
@RequestMapping("/api/auth/totp")
public class TotpController {

    private final UserRepository users;
    private final TotpService totpService;
    private final RecoveryCodeService recoveryCodes;
    private final AuditService audit;

    public TotpController(UserRepository users, TotpService totpService,
                           RecoveryCodeService recoveryCodes, AuditService audit) {
        this.users = users;
        this.totpService = totpService;
        this.recoveryCodes = recoveryCodes;
        this.audit = audit;
    }

    /**
     * Generates a new secret and stores it encrypted, but does NOT enable
     * 2FA yet — is2faEnabled() stays false until /verify confirms the user
     * actually has it working in an authenticator app. Calling this again
     * before verifying simply overwrites the pending secret.
     */
    @PostMapping("/setup")
    public TotpSetupResponse setup(Authentication authentication) {
        User user = (User) authentication.getPrincipal();

        TotpService.GeneratedSecret generated = totpService.generateSecret();
        user.setTotpSecretEnc(generated.encryptedSecret());
        users.save(user);

        String uri = totpService.buildOtpAuthUri(generated.rawSecret(), user.getEmail());
        return new TotpSetupResponse(uri, generated.rawSecret());
    }

    @PostMapping("/verify")
    public TotpVerifyResponse verify(@Valid @RequestBody TotpVerifyRequest req,
                                      Authentication authentication, HttpServletRequest http) {
        User user = (User) authentication.getPrincipal();

        if (user.getTotpSecretEnc() == null) {
            throw new TotpSetupRequiredException();
        }
        if (!totpService.verify(user.getTotpSecretEnc(), req.code())) {
            throw new InvalidTotpCodeException();
        }

        user.enableTotp();
        users.save(user);
        List<String> codes = recoveryCodes.regenerate(user.getId());

        audit.record("auth.totp.enabled", user.getId(), null, http.getRemoteAddr(), http.getHeader("User-Agent"));
        return new TotpVerifyResponse(codes);
    }

    /**
     * Requires the SAME proof login itself does (a current TOTP code or an
     * unused recovery code) — an authenticated session alone is not enough
     * to remove a security control, or a stolen access token would be
     * sufficient to strip 2FA off an account. Clears recovery codes too:
     * they're meaningless once there's no second factor to recover into.
     */
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@Valid @RequestBody TotpDisableRequest req,
                         Authentication authentication, HttpServletRequest http) {
        User user = (User) authentication.getPrincipal();

        if (!user.is2faEnabled()) {
            throw AppException.badRequest("Two-factor authentication is not enabled");
        }

        boolean validTotp = totpService.verify(user.getTotpSecretEnc(), req.code());
        boolean validRecovery = !validTotp && recoveryCodes.consume(user.getId(), req.code());
        if (!validTotp && !validRecovery) {
            throw new InvalidTotpCodeException();
        }

        user.disableTotp();
        users.save(user);
        recoveryCodes.clearAll(user.getId());

        audit.record("auth.totp.disabled", user.getId(), null, http.getRemoteAddr(), http.getHeader("User-Agent"));
    }
}
