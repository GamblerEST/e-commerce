package ee.shop.identity.web;

import ee.shop.common.security.EmailHasher;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account administration.
 *
 * The tests that matter here are the ones about an admin destroying their
 * own access, and about SUPPORT not quietly being an admin: role assignment
 * is how a compromised session becomes a permanent backdoor.
 */
@Tag("api")
@Tag("security")
@AutoConfigureMockMvc
class AdminUserControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private record Account(String token, UUID id, String email) {}

    private Account register(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@test.ee";
        var req = new RegisterRequest(email, "correct-horse-battery", "Admin Test User", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email_hash = ?", UUID.class, emailHasher.hash(email));
        return new Account(token, id, email);
    }

    private Account staff(String role) throws Exception {
        Account account = register(role.toLowerCase());
        jdbc.update("UPDATE users SET role = ?, totp_secret_enc = 'test-secret', totp_enabled_at = now() "
                + "WHERE id = ?", role, account.id());
        return account;
    }

    private String roleOf(UUID id) {
        return jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, id);
    }

    // ── the anti-lockout guards ──────────────────────────────────────────

    /**
     * There is no out-of-band admin recovery in this system. If the last
     * administrator demotes themselves, nobody can undo it — so the action
     * is refused rather than merely warned about.
     */
    @Test
    void anAdmin_cannotChangeTheirOwnRole() throws Exception {
        Account admin = staff("ADMIN");

        mvc.perform(put("/api/admin/users/" + admin.id() + "/role")
                        .param("role", "CUSTOMER")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isConflict());

        assertThat(roleOf(admin.id())).isEqualTo("ADMIN");
    }

    @Test
    void anAdmin_cannotDisableTheirOwnAccount() throws Exception {
        Account admin = staff("ADMIN");

        mvc.perform(put("/api/admin/users/" + admin.id() + "/disabled")
                        .param("disabled", "true")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isConflict());
    }

    /** Another admin can, which is why the self-guard doesn't strand anyone. */
    @Test
    void oneAdmin_canChangeAnotherAccountsRole() throws Exception {
        Account admin = staff("ADMIN");
        Account customer = register("customer");

        mvc.perform(put("/api/admin/users/" + customer.id() + "/role")
                        .param("role", "SUPPORT")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPPORT"));

        assertThat(roleOf(customer.id())).isEqualTo("SUPPORT");
    }

    // ── the support/admin boundary ───────────────────────────────────────

    /**
     * A support agent who can grant roles is an administrator with extra
     * steps — the entire reason the second role exists is that the everyday
     * job doesn't carry that power.
     */
    @Test
    void support_canReadAccounts_butCannotGrantRoles() throws Exception {
        Account agent = staff("SUPPORT");
        Account customer = register("customer");

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk());

        mvc.perform(put("/api/admin/users/" + customer.id() + "/role")
                        .param("role", "ADMIN")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());

        assertThat(roleOf(customer.id())).as("no privilege escalation").isEqualTo("CUSTOMER");
    }

    @Test
    void plainCustomers_cannotListAccounts() throws Exception {
        Account customer = register("customer");

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffWithoutTwoFactor_cannotListAccounts() throws Exception {
        Account agent = register("nofa");
        jdbc.update("UPDATE users SET role = 'SUPPORT' WHERE id = ?", agent.id());

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
    }

    // ── what the response may contain ────────────────────────────────────

    /**
     * The User entity carries passwordHash and totpSecretEnc. Neither may
     * ever reach a response body — not even an administrator's.
     */
    @Test
    void userListing_neverExposesSecrets() throws Exception {
        Account admin = staff("ADMIN");

        MvcResult result = mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("passwordHash").doesNotContain("password_hash");
        assertThat(body).doesNotContain("totpSecret").doesNotContain("totp_secret");
        assertThat(body).doesNotContain("argon2");
        // ...while still carrying what an admin actually needs.
        assertThat(body).contains("twoFactorEnabled").contains("role");
    }

    @Test
    void roleChange_isAudited() throws Exception {
        Account admin = staff("ADMIN");
        Account customer = register("customer");

        mvc.perform(put("/api/admin/users/" + customer.id() + "/role")
                        .param("role", "SUPPORT")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk());

        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'admin.user.role_changed'",
                Long.class, admin.id());
        assertThat(entries).as("who granted this role, and when").isEqualTo(1);
    }
}
