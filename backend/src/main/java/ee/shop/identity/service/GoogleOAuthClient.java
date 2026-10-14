package ee.shop.identity.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import ee.shop.config.GoogleOAuthProperties;
import ee.shop.identity.exception.OAuthExchangeFailedException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Talks to Google directly — deliberately NOT via spring-security-oauth2-client
 * (see GoogleOAuthProperties javadoc for why). The exchange happens once, here,
 * server-to-server; nothing downstream of OAuth2Controller ever sees or trusts
 * a Google-issued token again.
 */
@Service
public class GoogleOAuthClient {

    private final GoogleOAuthProperties props;
    private final RestClient http;

    public GoogleOAuthClient(GoogleOAuthProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(props.authorizationUri())
                .queryParam("client_id", props.clientId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("access_type", "online")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    /**
     * Exchanges an authorization code for Google's access token, then
     * immediately calls the userinfo endpoint with it — the access token
     * itself is never returned to or stored past this method.
     */
    public GoogleUserInfo exchangeCodeForUserInfo(String code) {
        TokenResponse token;
        try {
            token = http.post()
                    .uri(props.tokenUri())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("code=" + code
                            + "&client_id=" + props.clientId()
                            + "&client_secret=" + props.clientSecret()
                            + "&redirect_uri=" + props.redirectUri()
                            + "&grant_type=authorization_code")
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (Exception e) {
            throw new OAuthExchangeFailedException();
        }

        if (token == null || token.accessToken() == null) {
            throw new OAuthExchangeFailedException();
        }

        GoogleUserInfo userInfo;
        try {
            userInfo = http.get()
                    .uri(props.userInfoUri())
                    .header("Authorization", "Bearer " + token.accessToken())
                    .retrieve()
                    .body(GoogleUserInfo.class);
        } catch (Exception e) {
            throw new OAuthExchangeFailedException();
        }

        if (userInfo == null || userInfo.sub() == null || userInfo.email() == null) {
            throw new OAuthExchangeFailedException();
        }
        return userInfo;
    }

    // Same reasoning as RecaptchaService.SiteVerifyResponse: Google's real
    // responses carry many more fields than we read (expires_in, scope,
    // token_type, id_token; given_name, family_name, picture, locale, ...).
    // The global spring.jackson fail-on-unknown-properties=true is meant
    // for OUR OWN inbound request DTOs, not Google's response shape, which
    // we don't control and shouldn't have login break if Google adds a field.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoogleUserInfo(
            String sub,
            String email,
            @JsonProperty("email_verified") boolean emailVerified,
            String name
    ) {}
}
