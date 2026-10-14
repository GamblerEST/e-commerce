package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Thrown at login when 2FA is enabled and the supplied code (TOTP or
 * recovery) is missing or doesn't verify. ONE message for both cases —
 * credentials were already confirmed correct by this point (see
 * AuthController.login), so there's no enumeration concern left to protect,
 * but there's no reason to tell an attacker WHICH of the two code types they
 * got wrong either.
 */
public class TotpCodeRequiredException extends AppException {
    public TotpCodeRequiredException() {
        super("A valid TOTP or recovery code is required", HttpStatus.UNAUTHORIZED);
    }
}
