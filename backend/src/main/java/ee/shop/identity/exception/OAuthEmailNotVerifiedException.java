package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * A Google identity is not yet linked to any account, and its email matches
 * an EXISTING local account — but Google itself reports that email as
 * unverified. Auto-linking here would let anyone who can register an
 * unverified Google account under a victim's email address take over that
 * victim's existing local account. Refuse instead of guessing.
 */
public class OAuthEmailNotVerifiedException extends AppException {
    public OAuthEmailNotVerifiedException() {
        super("Google reports this email as unverified; cannot link accounts", HttpStatus.CONFLICT);
    }
}
