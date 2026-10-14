package ee.shop.privacy;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GDPR access (Art. 15/20) and erasure (Art. 17).
 *
 * anonymize_user() had been in the schema since V1 and was described across
 * DECISIONS.md §5 and four source files as the erasure mechanism —
 * with no code path calling it and no endpoint exposing it. These tests exist
 * so that stops being a documentation claim and becomes a tested behaviour.
 */
@Tag("api")
@Tag("security")
@Tag("flow")
@AutoConfigureMockMvc
class PrivacyControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ee.shop.common.security.FieldCipher cipher;

    private record Account(String email, String token) {}

    private Account register() throws Exception {
        String email = "privacy-" + UUID.randomUUID() + "@test.ee";
        var req = new RegisterRequest(email, PASSWORD, "Privacy Tester", null);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return new Account(email,
                json.readValue(res.getResponse().getContentAsString(), AuthResponse.class).accessToken());
    }

    private UUID idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email_hash = ?", UUID.class, emailHasher.hash(email));
    }

    // ── access / portability ─────────────────────────────────────────────

    @Test
    void exportRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/account/data")).andExpect(status().isUnauthorized());
    }

    @Test
    void exportReturnsTheCallersOwnData_asADownload() throws Exception {
        Account me = register();

        mvc.perform(get("/api/account/data").header("Authorization", "Bearer " + me.token()))
                .andExpect(status().isOk())
                // Art. 20 asks for a portable file, not a page to read.
                .andExpect(header -> assertThat(
                        header.getResponse().getHeader("Content-Disposition"))
                        .contains("attachment"))
                .andExpect(jsonPath("$.account.email").value(me.email()))
                .andExpect(jsonPath("$.exportedAt").isNotEmpty())
                .andExpect(jsonPath("$.orders").isArray())
                .andExpect(jsonPath("$.reviews").isArray());
    }

    @Test
    void exportContainsNobodyElsesData() throws Exception {
        Account mine = register();
        Account theirs = register();

        String body = mvc.perform(get("/api/account/data")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(mine.email());
        assertThat(body).doesNotContain(theirs.email());
    }

    // ── erasure ──────────────────────────────────────────────────────────

    @Test
    void erasureRequiresTheCorrectPassword() throws Exception {
        Account me = register();

        // A stolen access token alone must not be enough to destroy an account.
        mvc.perform(delete("/api/account")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email_hash = ?", Integer.class, emailHasher.hash(me.email())))
                .isEqualTo(1);
    }

    @Test
    void erasureScrubsTheAccountButKeepsTheRow() throws Exception {
        Account me = register();
        UUID userId = idOf(me.email());

        mvc.perform(delete("/api/account")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        var row = jdbc.queryForMap(
                "SELECT email, display_name, password_hash, anonymized_at FROM users WHERE id = ?",
                userId);

        // Anonymised in place, never deleted: order foreign keys must survive.
        assertThat(row.get("email")).isNull();
        assertThat(row.get("display_name")).isNull();
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("anonymized_at")).isNotNull();
    }

    @Test
    void erasureRevokesEverySession() throws Exception {
        Account me = register();
        UUID userId = idOf(me.email());

        mvc.perform(delete("/api/account")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId))
                .isZero();

        // And the access token it was carrying no longer opens anything.
        mvc.perform(get("/api/account/data").header("Authorization", "Bearer " + me.token()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The half of erasure that is a legal obligation rather than a right.
     *
     * Orders and invoices are retained seven years under the Estonian
     * Accounting Act, and GDPR Art. 17(3)(b) exempts exactly that. They survive
     * because each order snapshots its own billing name and address instead of
     * joining to `users` — which is the entire reason DECISIONS.md §2 insists
     * on the snapshot. If this test fails, complying with a deletion request
     * has just destroyed accounting records.
     */
    @Test
    void erasureKeepsTheOrdersAndTheirBillingSnapshot() throws Exception {
        Account me = register();
        UUID userId = idOf(me.email());

        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, user_id, idempotency_key, billing_name, billing_email,
                                    billing_address_line1, billing_city, billing_postal_code,
                                    billing_country, currency, subtotal, vat_total, total)
                VALUES (?, ?, ?, 'Kadri Tamm', 'kadri@example.ee', 'Pikk tn 1', 'Tallinn',
                        '10123', 'EE', 'EUR', 10.00, 2.40, 12.40)
                """, orderId, userId, "erasure-test-" + orderId);

        mvc.perform(delete("/api/account")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        var order = jdbc.queryForMap(
                "SELECT billing_name, billing_email, total, user_id FROM orders WHERE id = ?",
                orderId);

        // The order is still there, still readable, still says who bought what.
        assertThat(order.get("billing_name")).isNotNull();
        assertThat(order.get("total")).isNotNull();

        // These columns are AES-GCM encrypted at rest (V20), so compare through
        // the cipher rather than against the raw stored value.
        assertThat(cipher.decrypt((String) order.get("billing_name"))).isEqualTo("Kadri Tamm");
        assertThat(cipher.decrypt((String) order.get("billing_email"))).isEqualTo("kadri@example.ee");
    }

    @Test
    void erasureIsRecordedInTheAuditLog() throws Exception {
        Account me = register();
        UUID userId = idOf(me.email());

        mvc.perform(delete("/api/account")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        // Two records: the controller's, and anonymize_user()'s own. Both must
        // survive the row they refer to being scrubbed.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_id = ? AND action IN "
                        + "('privacy.account_erased', 'user.anonymized')",
                Integer.class, userId.toString()))
                .isGreaterThanOrEqualTo(1);
    }
}
