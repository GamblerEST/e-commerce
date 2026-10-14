package ee.shop.identity.service;

import ee.shop.identity.exception.OAuthAccountUnavailableException;
import ee.shop.identity.exception.OAuthEmailNotVerifiedException;
import ee.shop.identity.model.OAuthIdentity;
import ee.shop.common.security.EmailHasher;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.OAuthIdentityRepository;
import ee.shop.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Link-or-create logic for third-party identities, kept free of any HTTP
 * concern (GoogleOAuthClient's job) so it's testable without a real network
 * call to Google — see OAuthAccountServiceTest.
 *
 * Deliberately does NOT call AuditService itself: audit.record uses
 * REQUIRES_NEW (see its javadoc), which runs on a separate connection and
 * commits immediately. Calling it from inside THIS method's @Transactional
 * boundary — before the user row this method just inserted has committed —
 * makes that separate connection unable to see the row, and the audit
 * insert's FK to users(id) fails. The caller (OAuth2Controller) is NOT
 * transactional, so it audits after this method returns and its transaction
 * has committed — same pattern AuthController already uses.
 */
@Service
public class OAuthAccountService {

    private final UserRepository users;

    private final EmailHasher emailHasher;
    private final OAuthIdentityRepository identities;

    public OAuthAccountService(UserRepository users, OAuthIdentityRepository identities,
                              EmailHasher emailHasher) {
        this.emailHasher = emailHasher;
        this.users = users;
        this.identities = identities;
    }

    @Transactional
    public User linkOrCreate(String provider, GoogleOAuthClient.GoogleUserInfo profile) {
        Optional<OAuthIdentity> existingLink = identities.findByProviderAndSubject(provider, profile.sub());
        if (existingLink.isPresent()) {
            return users.findById(existingLink.get().getUserId())
                    .filter(User::isActive)
                    .orElseThrow(OAuthAccountUnavailableException::new);
        }

        Optional<User> existingByEmail = users.findActiveByEmailHash(emailHasher.hash(profile.email()));
        if (existingByEmail.isPresent()) {
            User user = existingByEmail.get();

            // Refuse to silently attach an unverified-at-Google email to
            // someone else's existing account — that would be a takeover
            // path for anyone who can register an unverified Google account
            // under a victim's email. See OAuthEmailNotVerifiedException.
            if (!profile.emailVerified()) {
                throw new OAuthEmailNotVerifiedException();
            }

            identities.save(new OAuthIdentity(user.getId(), provider, profile.sub()));
            return user;
        }

        // Brand new account. If Google reports the email verified, trust it
        // and skip our own verify-email round trip; otherwise the account is
        // created the same as a normal registration — unverified, pending
        // the standard flow.
        User user = new User(profile.email(), emailHasher.hash(profile.email()), null,
                profile.name() != null && !profile.name().isBlank() ? profile.name() : profile.email());
        if (profile.emailVerified()) {
            user.markEmailVerified();
        }
        user = users.save(user);
        identities.save(new OAuthIdentity(user.getId(), provider, profile.sub()));
        return user;
    }
}
