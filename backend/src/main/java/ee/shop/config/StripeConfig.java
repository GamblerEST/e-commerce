package ee.shop.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * The Stripe Java SDK reads its API key from a static field, not per-call —
 * there is no dependency-injected client to configure otherwise. Setting it
 * even when StripeProperties.isEnabled() is false is harmless: Stripe.apiKey
 * being blank just means the SDK fails clearly the moment something tries to
 * call it, same "degrade at point of use" behavior as RecaptchaService.
 */
@Configuration
public class StripeConfig {

    private final StripeProperties props;

    public StripeConfig(StripeProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        Stripe.apiKey = props.secretKey();
    }
}
