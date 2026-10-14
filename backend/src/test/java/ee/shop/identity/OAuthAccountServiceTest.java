package ee.shop.identity;

import ee.shop.common.security.EmailHasher;
import ee.shop.identity.exception.OAuthAccountUnavailableException;
import ee.shop.identity.exception.OAuthEmailNotVerifiedException;
import ee.shop.identity.model.OAuthIdentity;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.OAuthIdentityRepository;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.GoogleOAuthClient.GoogleUserInfo;
import ee.shop.identity.service.OAuthAccountService;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure link-or-create logic, no HTTP — see OAuth2ControllerTest for the
 * wired-up endpoint behavior with GoogleOAuthClient mocked.
 */
@Tag("api")
class OAuthAccountServiceTest extends IntegrationTest {

    @Autowired OAuthAccountService accountService;
    @Autowired UserRepository users;
    @Autowired EmailHasher emailHasher;
    @Autowired OAuthIdentityRepository identities;
    @Autowired JdbcTemplate jdbc;

    @Test
    void unknownSubjectAndUnknownEmail_createsANewVerifiedAccount() {
        var profile = new GoogleUserInfo("google-sub-1", "brandnew@test.ee", true, "New Person");

        User user = accountService.linkOrCreate("google", profile);

        assertThat(user.getId()).isNotNull();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(identities.findByProviderAndSubject("google", "google-sub-1"))
                .get().extracting(OAuthIdentity::getUserId).isEqualTo(user.getId());
    }

    @Test
    void unverifiedGoogleEmail_stillCreatesAnUnverifiedAccount_whenNoExistingUserOwnsIt() {
        var profile = new GoogleUserInfo("google-sub-2", "unverified-new@test.ee", false, "Someone");

        User user = accountService.linkOrCreate("google", profile);

        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void knownSubject_returnsTheSameLinkedAccountOnSubsequentLogins() {
        var profile = new GoogleUserInfo("google-sub-3", "repeat@test.ee", true, "Repeat User");

        User first = accountService.linkOrCreate("google", profile);
        User second = accountService.linkOrCreate("google", profile);

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void newGoogleIdentityWithVerifiedEmail_linksToExistingLocalAccount() {
        User existing = users.save(new User("linkme@test.ee", emailHasher.hash("linkme@test.ee"), "{argon2}$fake", "Existing User"));
        var profile = new GoogleUserInfo("google-sub-4", "linkme@test.ee", true, "Existing User");

        User linked = accountService.linkOrCreate("google", profile);

        assertThat(linked.getId()).isEqualTo(existing.getId());
        assertThat(identities.findByProviderAndSubject("google", "google-sub-4"))
                .get().extracting(OAuthIdentity::getUserId).isEqualTo(existing.getId());
    }

    @Test
    void newGoogleIdentityWithUnverifiedEmail_refusesToLinkToExistingAccount() {
        users.save(new User("victim@test.ee", emailHasher.hash("victim@test.ee"), "{argon2}$fake", "Victim"));
        var profile = new GoogleUserInfo("google-sub-5", "victim@test.ee", false, "Attacker-controlled name");

        // An attacker who registers an unverified Google account under the
        // victim's email must NOT be able to attach it to the victim's
        // existing account — see OAuthEmailNotVerifiedException.
        assertThatThrownBy(() -> accountService.linkOrCreate("google", profile))
                .isInstanceOf(OAuthEmailNotVerifiedException.class);

        assertThat(identities.findByProviderAndSubject("google", "google-sub-5")).isEmpty();
    }

    @Test
    void linkedButDisabledAccount_isRejected() {
        User user = users.save(new User("disabled@test.ee", emailHasher.hash("disabled@test.ee"), "{argon2}$fake", "Disabled"));
        identities.save(new OAuthIdentity(user.getId(), "google", "google-sub-6"));

        // No admin-disable flow existed when this was written — set the
        // column directly to exercise the real isActive() check regardless.
        jdbc.update("UPDATE users SET disabled_at = now() WHERE id = ?", user.getId());

        var profile = new GoogleUserInfo("google-sub-6", "disabled@test.ee", true, "Disabled");

        assertThatThrownBy(() -> accountService.linkOrCreate("google", profile))
                .isInstanceOf(OAuthAccountUnavailableException.class);
    }
}
