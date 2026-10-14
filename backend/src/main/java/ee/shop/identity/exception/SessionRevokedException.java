package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/** Replay detected, or an explicit revocation — see TokenService.rotate(...). */
public class SessionRevokedException extends AppException {
    public SessionRevokedException() {
        super("Session revoked. Please sign in again.", HttpStatus.UNAUTHORIZED);
    }
}
