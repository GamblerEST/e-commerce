package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/** Thrown by POST /api/auth/totp/verify when the confirmation code is wrong. */
public class InvalidTotpCodeException extends AppException {
    public InvalidTotpCodeException() {
        super("Invalid TOTP code", HttpStatus.BAD_REQUEST);
    }
}
