package ee.shop.identity.web;

import tools.jackson.databind.ObjectMapper;
import ee.shop.identity.service.GoogleOAuthClient;
import ee.shop.identity.web.dto.AuthorizeResponse;
import ee.shop.identity.web.dto.OAuthCallbackRequest;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GoogleOAuthClient is a SPY, not a full mock — buildAuthorizationUrl is a
 * pure, local, non-HTTP method (just URL construction) and should run for
 * real; only exchangeCodeForUserInfo (the method that actually calls out to
 * Google) gets stubbed per-test to avoid a real network call. A full
 * @MockitoBean here would silently return null from buildAuthorizationUrl
 * too, since Mockito's default answer for an unstubbed method is null — that
 * bug is exactly what the first version of this test caught.
 *
 * app.oauth2.google.client-id/secret are given dummy non-blank values here
 * specifically (the base IntegrationTest leaves them blank, matching
 * production's "not configured yet" default) so the "configured" code path
 * is actually exercised, on top of OAuth2NotConfiguredTest.
 */
@Tag("api")
@Tag("flow")
@AutoConfigureMockMvc
class OAuth2ControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean GoogleOAuthClient googleOAuthClient;

    @DynamicPropertySource
    static void googleOAuthConfigured(DynamicPropertyRegistry registry) {
        registry.add("app.oauth2.google.client-id", () -> "test-client-id");
        registry.add("app.oauth2.google.client-secret", () -> "test-client-secret");
    }

    private String requestAuthorizationState() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/oauth2/google/authorize"))
                .andExpect(status().isOk())
                .andReturn();
        AuthorizeResponse body = json.readValue(result.getResponse().getContentAsString(), AuthorizeResponse.class);
        // The authorization URL embeds state=<value> as a query param.
        String url = body.authorizationUrl();
        int idx = url.indexOf("state=");
        assertThat(idx).as("authorization URL contains a state param").isPositive();
        String rest = url.substring(idx + "state=".length());
        int amp = rest.indexOf('&');
        return amp >= 0 ? rest.substring(0, amp) : rest;
    }

    @Test
    void authorize_buildsAGoogleUrlContainingClientIdAndState() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/oauth2/google/authorize"))
                .andExpect(status().isOk())
                .andReturn();

        AuthorizeResponse body = json.readValue(result.getResponse().getContentAsString(), AuthorizeResponse.class);
        assertThat(body.authorizationUrl())
                .contains("client_id=test-client-id")
                .contains("state=");
    }

    @Test
    void callback_withRealStateAndMockedGoogle_createsAccountAndIssuesTokens() throws Exception {
        doReturn(new GoogleOAuthClient.GoogleUserInfo(
                        "google-sub-happy", "oauthuser@test.ee", true, "OAuth User"))
                .when(googleOAuthClient).exchangeCodeForUserInfo(any());

        String state = requestAuthorizationState();

        MvcResult result = mvc.perform(post("/api/auth/oauth2/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new OAuthCallbackRequest("fake-code", state))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("accessToken");
        // Two Set-Cookie headers land on this response (CSRF + refresh) —
        // getHeader() would silently return only the first.
        String refreshCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("refresh_token="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no refresh_token Set-Cookie header"));
        assertThat(refreshCookie).contains("HttpOnly").contains("Secure");
    }

    @Test
    void callback_stateIsSingleUse() throws Exception {
        doReturn(new GoogleOAuthClient.GoogleUserInfo(
                        "google-sub-replay", "oauthreplay@test.ee", true, "Replay User"))
                .when(googleOAuthClient).exchangeCodeForUserInfo(any());

        String state = requestAuthorizationState();
        var req = new OAuthCallbackRequest("fake-code", state);

        mvc.perform(post("/api/auth/oauth2/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk());

        // Replaying the same state (e.g. a forged/copied callback URL) must
        // not be accepted twice.
        mvc.perform(post("/api/auth/oauth2/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void callback_withUnknownState_isBadRequest() throws Exception {
        mvc.perform(post("/api/auth/oauth2/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new OAuthCallbackRequest("fake-code", "never-issued"))))
                .andExpect(status().isBadRequest());
    }
}
