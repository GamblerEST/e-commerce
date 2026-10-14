package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Deliberately carries ONE message regardless of whether the email was
 * unknown or the password was wrong — see AuthControllerTest's
 * unknownEmailAndWrongPassword_returnTheSameUnauthorizedMessage. Distinguishing
 * the two in the response would let an attacker enumerate registered emails.
 */
public class InvalidCredentialsException extends AppException {
    public InvalidCredentialsException() {
        super("Invalid email or password", HttpStatus.UNAUTHORIZED);
    }
}
