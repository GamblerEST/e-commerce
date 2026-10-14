package ee.shop.identity.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ee.shop.config.RecaptchaProperties;
import ee.shop.identity.exception.RecaptchaVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Verifies a client-supplied reCAPTCHA token against Google's siteverify
 * endpoint. Scoped to registration only (see AuthController) — login abuse
 * is rate limiting's job, not reCAPTCHA's.
 */
@Service
public class RecaptchaService {

    private static final Logger log = LoggerFactory.getLogger(RecaptchaService.class);

    private final RecaptchaProperties props;
    private final RestClient http;

    public RecaptchaService(RecaptchaProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();

        if (!props.isEnabled()) {
            log.warn("reCAPTCHA verification is DISABLED — app.recaptcha.secret is not set. " +
                    "Registration will accept any request unchecked. Do not deploy to " +
                    "production like this.");
        }
    }

    /**
     * @throws RecaptchaVerificationException if a real verification was attempted and failed.
     *         Silently passes (returns) when disabled — see the constructor warning.
     */
    public void verify(String token, String remoteIp) {
        if (!props.isEnabled()) {
            return;
        }

        if (token == null || token.isBlank()) {
            throw new RecaptchaVerificationException();
        }

        SiteVerifyResponse result;
        try {
            result = http.post()
                    .uri(props.verifyUrl())
                    .body("secret=" + props.secret() + "&response=" + token + "&remoteip=" + remoteIp)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .retrieve()
                    .body(SiteVerifyResponse.class);
        } catch (Exception e) {
            // A Google outage must not be indistinguishable from "this request
            // is a bot" — but it also must not silently waive verification.
            // Fail the request loudly instead of either extreme.
            log.error("reCAPTCHA verification call failed", e);
            throw new RecaptchaVerificationException();
        }

        if (result == null || !result.success()) {
            throw new RecaptchaVerificationException();
        }

        // score is v3-only; a v2 checkbox response has no score field and is
        // judged on success alone.
        if (result.score() != null && result.score() < props.minScore()) {
            throw new RecaptchaVerificationException();
        }
    }

    // Google's real response includes fields this record doesn't care about
    // (challenge_ts, error-codes, ...) — spring.jackson.deserialization.
    // fail-on-unknown-properties=true is deliberately strict for OUR OWN
    // inbound request DTOs (application.yml), but that same global
    // ObjectMapper also backs this outbound RestClient call. We don't
    // control Google's response shape and don't want a future field Google
    // adds to break login, so this one opts back out explicitly.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SiteVerifyResponse(
            boolean success,
            Double score,
            String action,
            String hostname
    ) {}
}
