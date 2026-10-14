package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deliberately allowed to be blank, same reasoning as RecaptchaProperties —
 * Stripe keys come from a third-party account this project may not have set
 * up yet in every environment. A missing key degrades payments (checkout
 * still creates the order; PaymentService just can't create a PaymentIntent
 * for it), which is real but isn't the "app must refuse to boot" failure
 * mode DB_URL/JWT_SECRET guard against.
 */
@ConfigurationProperties(prefix = "app.stripe")
public record StripeProperties(String secretKey, String webhookSecret) {
    public boolean isEnabled() {
        return secretKey != null && !secretKey.isBlank();
    }
}
