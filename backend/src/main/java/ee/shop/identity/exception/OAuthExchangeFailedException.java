package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/** The code exchange or userinfo call to Google failed or returned garbage. */
public class OAuthExchangeFailedException extends AppException {
    public OAuthExchangeFailedException() {
        super("Could not complete sign-in with Google, please try again", HttpStatus.BAD_GATEWAY);
    }
}
