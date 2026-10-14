package ee.shop.privacy.web;

import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.identity.model.User;
import ee.shop.identity.service.TokenService;
import ee.shop.identity.web.dto.DeleteAccountRequest;
import ee.shop.identity.web.dto.PersonalDataExport;
import ee.shop.privacy.service.PersonalDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * The two rights a customer can exercise over their own data: get a copy of it
 * (GDPR Art. 15/20) and have it erased (Art. 17).
 *
 * Both are scoped strictly to the CALLER. There is no user id in either path —
 * it comes from the authenticated principal — so there is no version of these
 * endpoints that can be pointed at somebody else's account.
 */
@RestController
@RequestMapping("/api/account")
@Tag(name = "Privacy")
public class PrivacyController {

    private final PersonalDataService personalData;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService audit;

    public PrivacyController(PersonalDataService personalData, PasswordEncoder passwordEncoder,
                              TokenService tokenService, AuditService audit) {
        this.personalData = personalData;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.audit = audit;
    }

    /**
     * Served as a download rather than a plain JSON body, because the point of
     * Art. 20 portability is that the person ends up holding a file they can
     * take elsewhere.
     */
    @GetMapping("/data")
    @Operation(summary = "Download everything this shop holds about you (GDPR Art. 15/20)")
    public ResponseEntity<PersonalDataExport> exportMyData(Authentication authentication,
                                                            HttpServletRequest http) {
        User user = (User) authentication.getPrincipal();

        // A data export is a security-relevant event: it is the single request
        // that returns the most personal data in one response, so "who
        // downloaded this, and when" needs to be answerable afterwards.
        audit.record("privacy.data_exported", user.getId(), null,
                http.getRemoteAddr(), http.getHeader("User-Agent"));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"estonian-shop-my-data.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(personalData.export(user.getId()));
    }

    /**
     * Erasure. Irreversible, so it is re-authenticated with the password —
     * same reasoning as disabling 2FA: a stolen access token alone must not be
     * enough to destroy an account.
     *
     * What this does NOT delete is the point: orders and invoices are retained
     * seven years under the Estonian Accounting Act, which GDPR Art. 17(3)(b)
     * explicitly exempts. They keep their own snapshot of the billing name and
     * address, so the books stay intact while the account itself is scrubbed.
     */
    @DeleteMapping
    @Operation(summary = "Erase your account (GDPR Art. 17). Orders are retained by law.")
    public ResponseEntity<Void> deleteMyAccount(@Valid @RequestBody DeleteAccountRequest req,
                                                 Authentication authentication,
                                                 HttpServletRequest http) {
        User user = (User) authentication.getPrincipal();

        // An OAuth-only account has no password to confirm against. Refuse
        // rather than waive the check — silently skipping re-authentication
        // for a subset of accounts is how a control becomes optional.
        if (user.getPasswordHash() == null) {
            throw AppException.badRequest(
                    "Set a password before deleting an account created through Google sign-in.");
        }
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw AppException.unauthorized("Password is incorrect");
        }

        // Audited BEFORE the erasure: anonymize_user() nulls the row this
        // record refers to, and the audit entry has to survive that. It stores
        // the user id, which is not personal data on its own once the row it
        // pointed at holds nothing.
        audit.record("privacy.account_erased", user.getId(), null,
                http.getRemoteAddr(), http.getHeader("User-Agent"));

        personalData.erase(user.getId());

        // anonymize_user() already deletes every refresh token; this clears the
        // access-token side too, so the session cannot outlive the account.
        tokenService.revokeAllSessions(user.getId(), "account_erased");

        return ResponseEntity.noContent().build();
    }
}
