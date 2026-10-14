package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Montonio, for Estonian bank-link payments.
 *
 * Off unless explicitly switched on. When it is on, every value is required and
 * the application refuses to start without them: a payment method that appears
 * at checkout and then fails for every customer is worse than one that is not
 * offered. There is deliberately no default API URL either, so a production box
 * can never quietly send real customers to the sandbox.
 *
 * @param accessKey not secret (it travels inside every signed request), but set
 *                  alongside the secret key because the two are issued together
 */
@ConfigurationProperties(prefix = "app.montonio")
public record MontonioProperties(boolean enabled, String apiUrl, String accessKey, String secretKey) {

    private static final String HINT = "Set MONTONIO_API_URL and MONTONIO_ACCESS_KEY, put the store's secret key "
            + "from the Montonio Partner System in deploy/secrets/montonio_secret_key, or set MONTONIO_ENABLED=false.";

    public MontonioProperties {
        if (enabled) {
            RequiredSecrets.requireResolved(apiUrl, "app.montonio.api-url", HINT);
            RequiredSecrets.requireResolved(accessKey, "app.montonio.access-key", HINT);
            RequiredSecrets.requireResolved(secretKey, "app.montonio.secret-key", HINT);
        }
    }
}
