package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deliberately allowed to be blank, unlike JwtProperties.secret or DB_URL.
 * reCAPTCHA keys come from a third-party (Google) registration this project
 * hasn't done yet — see RecaptchaService for what happens when secret is
 * blank. This is NOT the "config has no default" pattern the rest of the app
 * follows: that pattern is for config whose ABSENCE would let a misconfigured
 * box run silently wrong (wrong DB, no signing key). A missing reCAPTCHA
 * secret degrades bot protection, which is real but is not that failure mode,
 * so we let it boot and log loudly instead of refusing to start.
 */
@ConfigurationProperties(prefix = "app.recaptcha")
public record RecaptchaProperties(
        String secret,
        String verifyUrl,
        double minScore
) {
    public boolean isEnabled() {
        return secret != null && !secret.isBlank();
    }
}
