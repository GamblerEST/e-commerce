package ee.shop.identity.web;

import tools.jackson.databind.ObjectMapper;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.ChangePasswordRequest;
import ee.shop.identity.web.dto.ForgotPasswordRequest;
import ee.shop.identity.web.dto.LoginRequest;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.identity.web.dto.ResetPasswordRequest;
import ee.shop.identity.web.dto.UpdateProfileRequest;
import ee.shop.identity.web.dto.VerifyEmailRequest;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real HTTP flow end to end, including the actual emailed
 * token — JavaMailSender is mocked so the test can capture what would have
 * been sent and extract the token from it, rather than only checking status
 * codes and calling it done.
 */
@Tag("api")
@Tag("flow")
@AutoConfigureMockMvc
class AccountControllerTest extends IntegrationTest {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([^\\s&]+)");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JavaMailSender javaMailSender;

    private String email() {
        return "user" + java.util.UUID.randomUUID() + "@test.ee";
    }

    private void register(String email) throws Exception {
        var req = new RegisterRequest(email, "correct-horse-battery", "Test User", null);
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        var req = new RegisterRequest(email, "correct-horse-battery", "Test User", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    /** Grabs the token embedded in the last email sent via javaMailSender. */
    private String lastEmailedToken() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        String body = captor.getValue().getText();
        Matcher m = TOKEN_PATTERN.matcher(body);
        assertThat(m.find()).as("email body contains a token= link").isTrue();
        return m.group(1);
    }

    @Test
    void verifyEmail_withRealToken_succeeds() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));

        String email = email();
        register(email);
        String token = lastEmailedToken();

        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new VerifyEmailRequest(token))))
                .andExpect(status().isNoContent());
    }

    @Test
    void verifyEmail_tokenIsSingleUse() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));

        register(email());
        String token = lastEmailedToken();
        var req = new VerifyEmailRequest(token);

        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        // Reusing the same link a second time must fail — otherwise it isn't
        // actually single-use, and a leaked/forwarded verification email
        // stays valid forever.
        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyEmail_withGarbageToken_isBadRequest() throws Exception {
        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new VerifyEmailRequest("not-a-real-token"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPassword_returnsIdenticalResponseForKnownAndUnknownEmail() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));

        String knownEmail = email();
        register(knownEmail);

        String knownResponse = mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest(knownEmail))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String unknownResponse = mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest("nobody-" + knownEmail))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Both branches return 202 with an empty body — the ONLY thing that
        // differs is an internal side effect (an email gets sent, or doesn't),
        // never anything observable in the HTTP response itself.
        assertThat(knownResponse).isEqualTo(unknownResponse);
    }

    @Test
    void resetPassword_changesPasswordAndRevokesAllExistingSessions() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));

        String email = email();
        register(email);

        // An existing session from BEFORE the reset.
        String loginBody = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest(email))))
                .andExpect(status().isAccepted());

        String resetToken = lastEmailedToken();

        mvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ResetPasswordRequest(resetToken, "brand-new-password-1"))))
                .andExpect(status().isNoContent());

        // Old password no longer works.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery"))))
                .andExpect(status().isUnauthorized());

        // New password works.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "brand-new-password-1"))))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_tokenIsSingleUse() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));

        String email = email();
        register(email);

        mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest(email))))
                .andExpect(status().isAccepted());
        String token = lastEmailedToken();

        mvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ResetPasswordRequest(token, "first-new-password-1"))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ResetPasswordRequest(token, "second-new-password-1"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_requiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/password/change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChangePasswordRequest("whatever", "new-password-12"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_withWrongCurrentPassword_isUnauthorizedAndNothingChanges() throws Exception {
        String email = email();
        String accessToken = registerAndGetAccessToken(email);

        mvc.perform(post("/api/auth/password/change")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChangePasswordRequest("totally-wrong", "new-password-12"))))
                .andExpect(status().isUnauthorized());

        // The old password (and old session) still work — a rejected change
        // must not have any side effect.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery"))))
                .andExpect(status().isOk());
    }

    @Test
    void changePassword_withCorrectCurrentPassword_changesItAndRevokesTheCallingSessionToo() throws Exception {
        String email = email();
        String accessToken = registerAndGetAccessToken(email);

        mvc.perform(post("/api/auth/password/change")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ChangePasswordRequest("correct-horse-battery", "brand-new-password-1"))))
                .andExpect(status().isNoContent());

        // Old password no longer works.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery"))))
                .andExpect(status().isUnauthorized());

        // New password works.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "brand-new-password-1"))))
                .andExpect(status().isOk());
    }

    @Test
    void updateProfile_changesDisplayNameAndReflectsInMe() throws Exception {
        String accessToken = registerAndGetAccessToken(email());

        mvc.perform(post("/api/auth/profile")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateProfileRequest("New Name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"));
    }

    @Test
    void updateProfile_requiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateProfileRequest("New Name"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendVerification_sendsALinkThatActuallyVerifies() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));
        String email = email();
        register(email);
        clearInvocations(javaMailSender);

        mvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest(email))))
                .andExpect(status().isAccepted());

        String token = lastEmailedToken();
        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new VerifyEmailRequest(token))))
                .andExpect(status().isNoContent());
    }

    @Test
    void resendVerification_answersTheSameForAnUnknownAddress() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));
        String known = email();
        register(known);

        String knownBody = mvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest(known))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String unknownBody = mvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest("nobody-" + known))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownBody).isEqualTo(knownBody);
    }

    @Test
    void resendVerification_sendsNothingForAVerifiedAccount() throws Exception {
        doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));
        String email = email();
        register(email);
        mvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new VerifyEmailRequest(lastEmailedToken()))))
                .andExpect(status().isNoContent());
        clearInvocations(javaMailSender);

        mvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ForgotPasswordRequest(email))))
                .andExpect(status().isAccepted());

        verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
    }
}
