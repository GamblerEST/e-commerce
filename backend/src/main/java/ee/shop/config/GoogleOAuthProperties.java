package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blank client-id/secret is a valid, supported state — same reasoning as
 * RecaptchaProperties. Google OAuth registration is a third-party step this
 * project hasn't done yet; a missing key disables "Continue with Google"
 * (OAuth2Controller returns a clear error), it does not point the app at the
 * wrong infrastructure the way a missing DB_URL would.
 *
 * Deliberately NOT using spring-boot-starter-oauth2-client's
 * spring.security.oauth2.client.registration.* auto-configuration: that
 * machinery constructs a real ClientRegistration the moment ANY
 * registration.* property is present, even blank, and throws at boot (this
 * bit us once already — see application.yml's OAuth2 comment). It also
 * establishes trust in the provider's own OAuth2AuthenticationToken, where
 * This project wants the opposite: exchange the code ourselves, then
 * issue OUR OWN token pair and never trust Google's tokens past that point.
 */
@ConfigurationProperties(prefix = "app.oauth2.google")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authorizationUri,
        String tokenUri,
        String userInfoUri
) {
    public boolean isEnabled() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }
}
