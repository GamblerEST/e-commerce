package ee.shop.identity.web;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the ACTUAL production default: no GOOGLE_CLIENT_ID configured
 * (IntegrationTest leaves it blank, same as OAuth2Controller sees when this
 * project hasn't registered with Google yet — see GoogleOAuthProperties).
 * Deliberately a separate class from OAuth2ControllerTest, which overrides
 * the client-id for its own tests and would otherwise mask this behavior.
 */
@Tag("api")
@AutoConfigureMockMvc
class OAuth2NotConfiguredTest extends IntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void authorize_withoutGoogleCredentials_returnsServiceUnavailable() throws Exception {
        mvc.perform(get("/api/auth/oauth2/google/authorize"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void callback_withoutGoogleCredentials_returnsServiceUnavailable() throws Exception {
        mvc.perform(post("/api/auth/oauth2/google/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"state\":\"y\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
