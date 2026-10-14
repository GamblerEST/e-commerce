package ee.shop.identity.web;

import ee.shop.common.security.EmailHasher;
import tools.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.web.dto.*;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("api")
@Tag("security")
@AutoConfigureMockMvc
class TotpControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired EmailHasher emailHasher;
    @Autowired JdbcTemplate jdbc;

    private String email() {
        return "user" + java.util.UUID.randomUUID() + "@test.ee";
    }

    private String register(String email) throws Exception {
        var req = new RegisterRequest(email, "correct-horse-battery", "Test User", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    /** Generates a currently-valid 6-digit code for the given raw secret,
     *  the same way an authenticator app (or TotpService.verify) would. */
    private String validCodeFor(String rawSecret) throws Exception {
        long timeCounter = Instant.now().getEpochSecond() / 30;
        return new DefaultCodeGenerator().generate(rawSecret, timeCounter);
    }

    private TotpSetupResponse setup(String accessToken) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/totp/setup")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), TotpSetupResponse.class);
    }

    @Test
    void setup_returnsAnOtpauthUriAndSecret() throws Exception {
        String accessToken = register(email());
        TotpSetupResponse setup = setup(accessToken);

        assertThat(setup.secret()).isNotBlank();
        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/").contains("secret=" + setup.secret());
    }

    @Test
    void verify_withValidCode_enablesTwoFactorAndReturnsRecoveryCodes() throws Exception {
        String accessToken = register(email());
        TotpSetupResponse setup = setup(accessToken);
        String code = validCodeFor(setup.secret());

        MvcResult result = mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest(code))))
                .andExpect(status().isOk())
                .andReturn();

        TotpVerifyResponse body = json.readValue(result.getResponse().getContentAsString(), TotpVerifyResponse.class);
        assertThat(body.recoveryCodes()).hasSize(10);
    }

    @Test
    void verify_withWrongCode_isBadRequest() throws Exception {
        String accessToken = register(email());
        setup(accessToken);

        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest("000000"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verify_withoutPriorSetup_isBadRequest() throws Exception {
        String accessToken = register(email());

        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest("123456"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_withTwoFactorEnabled_requiresACode() throws Exception {
        String email = email();
        String accessToken = register(email);
        TotpSetupResponse setupResp = setup(accessToken);
        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest(validCodeFor(setupResp.secret())))))
                .andExpect(status().isOk());

        // Password alone is no longer enough.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery"))))
                .andExpect(status().isUnauthorized());

        // Password + valid TOTP code succeeds.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new LoginRequest(email, "correct-horse-battery", validCodeFor(setupResp.secret())))))
                .andExpect(status().isOk());
    }

    @Test
    void login_withRecoveryCode_worksOnceThenFails() throws Exception {
        String email = email();
        String accessToken = register(email);
        TotpSetupResponse setupResp = setup(accessToken);
        MvcResult verifyResult = mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest(validCodeFor(setupResp.secret())))))
                .andExpect(status().isOk())
                .andReturn();
        String recoveryCode = json.readValue(verifyResult.getResponse().getContentAsString(), TotpVerifyResponse.class)
                .recoveryCodes().get(0);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery", recoveryCode))))
                .andExpect(status().isOk());

        // The same recovery code must not work a second time.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery", recoveryCode))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disable_withValidCode_turnsOffTwoFactorAndClearsRecoveryCodes() throws Exception {
        String email = email();
        String accessToken = register(email);
        TotpSetupResponse setupResp = setup(accessToken);
        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest(validCodeFor(setupResp.secret())))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/totp/disable")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpDisableRequest(validCodeFor(setupResp.secret())))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totpEnabled").value(false));

        // Login no longer even asks for a code — 2FA is genuinely off, not
        // just hidden.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery"))))
                .andExpect(status().isOk());

        User user = users.findActiveByEmailHash(emailHasher.hash(email)).orElseThrow();
        Long remainingCodes = jdbc.queryForObject(
                "SELECT count(*) FROM recovery_codes WHERE user_id = ?", Long.class, user.getId());
        assertThat(remainingCodes).isZero();
    }

    @Test
    void disable_withWrongCode_isBadRequestAndTwoFactorStaysOn() throws Exception {
        String accessToken = register(email());
        TotpSetupResponse setupResp = setup(accessToken);
        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest(validCodeFor(setupResp.secret())))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/totp/disable")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpDisableRequest("000000"))))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totpEnabled").value(true));
    }

    @Test
    void disable_whenNotEnabled_isBadRequest() throws Exception {
        String accessToken = register(email());

        mvc.perform(post("/api/auth/totp/disable")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpDisableRequest("000000"))))
                .andExpect(status().isBadRequest());
    }

    /**
     * The Account page's "is 2FA already on?" state comes entirely from
     * this field — without it, the UI has no way to distinguish a fresh
     * account from one that already has 2FA configured (found via manual
     * review testing: the setup page looked identical either way).
     */
    @Test
    void me_reflectsTwoFactorStatus() throws Exception {
        String accessToken = register(email());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totpEnabled").value(false));

        TotpSetupResponse setupResp = setup(accessToken);
        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new TotpVerifyRequest(validCodeFor(setupResp.secret())))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totpEnabled").value(true));
    }

    /**
     * Admins must have a second factor -- but the enforcement point is the
     * admin endpoints (@StaffOnly requires principal.is2faEnabled()), NOT
     * login.
     *
     * Blocking the login itself would risk a permanent lockout: an admin
     * whose authenticator is lost could not sign in at all, and there is no
     * out-of-band console to recover from. Letting them in with no admin
     * rights, and recording the fact, keeps recovery possible while still
     * refusing every privileged action. This asserts that audit trail exists.
     */
    @Test
    void adminLoginWithoutTwoFactor_succeedsButIsAudited() throws Exception {
        String email = email();
        register(email);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_hash = ?", emailHasher.hash(email));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "correct-horse-battery"))))
                .andExpect(status().isOk());

        User admin = users.findActiveByEmailHash(emailHasher.hash(email)).orElseThrow();
        Long auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.admin_login_without_2fa'",
                Long.class, admin.getId());
        assertThat(auditCount).isEqualTo(1);
    }
}
