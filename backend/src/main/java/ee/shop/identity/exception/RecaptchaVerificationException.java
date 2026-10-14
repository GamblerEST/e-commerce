package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Deliberately carries ONE generic client-facing message regardless of the
 * internal reason (missing token, Google outage, low score, failed check) —
 * telling a caller exactly why their captcha attempt failed is free
 * reconnaissance for someone probing the defense. RecaptchaService logs the
 * real reason server-side.
 */
public class RecaptchaVerificationException extends AppException {
    public RecaptchaVerificationException() {
        super("reCAPTCHA verification failed", HttpStatus.BAD_REQUEST);
    }
}
