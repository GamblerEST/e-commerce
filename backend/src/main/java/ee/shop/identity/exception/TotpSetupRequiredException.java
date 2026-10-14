package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/** POST /api/auth/totp/verify called before /totp/setup ever ran. */
public class TotpSetupRequiredException extends AppException {
    public TotpSetupRequiredException() {
        super("Call /api/auth/totp/setup first", HttpStatus.BAD_REQUEST);
    }
}
