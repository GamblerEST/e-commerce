package ee.shop.common.web;

import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An unknown path must answer 404 to a caller who is allowed to ask — not 500.
 *
 * Without this, Spring's NoResourceFoundException fell through to the
 * catch-all handler, so a mistyped URL reported "500 An unexpected error
 * occurred" and logged a full stack trace. That buries genuine 500s in noise
 * and, in development, sends you hunting for a bug that is really a typo.
 *
 * Note the requests here are AUTHENTICATED. To an anonymous caller every
 * unknown path answers 401, because Spring Security's authenticated()
 * catch-all runs before routing — which is the behaviour we want and is
 * asserted below: it means an anonymous prober cannot use status codes to
 * map which endpoints exist.
 */
@Tag("api")
@Tag("security")
@AutoConfigureMockMvc
class NotFoundHandlingTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String token() throws Exception {
        var req = new RegisterRequest("notfound-" + UUID.randomUUID() + "@test.ee",
                "correct-horse-battery", "Not Found Tester", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class)
                .accessToken();
    }

    @Test
    void unknownApiPath_is404NotServerError() throws Exception {
        mvc.perform(get("/api/definitely/not/a/real/endpoint")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownPath_returnsTheStandardErrorShape() throws Exception {
        mvc.perform(get("/api/nope").header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not found"));
    }

    @Test
    void unknownPath_doesNotEchoTheRequestedPathBack() throws Exception {
        // Reflecting caller-controlled input into an error body is how a
        // probing tool confirms what it reached, and becomes a reflected-XSS
        // sink the moment anything renders that message.
        mvc.perform(get("/api/not-a-real-path-scriptmarker")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("scriptmarker"))));
    }

    /**
     * An anonymous caller must not be able to tell "this path does not exist"
     * from "this path exists but needs a login" — that difference is a free
     * endpoint map for anyone probing the API.
     */
    @Test
    void anonymousCaller_cannotDistinguishMissingFromProtected() throws Exception {
        mvc.perform(get("/api/definitely/not/a/real/endpoint"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders")) // exists, but requires authentication
                .andExpect(status().isUnauthorized());
    }
}
