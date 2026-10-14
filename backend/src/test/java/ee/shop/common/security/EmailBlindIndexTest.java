package ee.shop.common.security;

import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email addresses are encrypted at rest and found through a blind index (V24).
 *
 * The address was the one piece of personal data left in clear text, because an
 * AES-GCM column cannot be searched or uniquely indexed. These tests pin both
 * halves: that the stored value is unreadable, and that login still works.
 */
@Tag("security")
@Tag("api")
@AutoConfigureMockMvc
class EmailBlindIndexTest extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailHasher emailHasher;
    @Autowired UserRepository users;

    private void register(String email) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, "Blind Index", null))))
                .andExpect(status().isCreated());
    }

    // ── the hash itself ──────────────────────────────────────────────────

    @Test
    void theHashIsDeterministic_orNothingCouldBeFound() {
        assertThat(emailHasher.hash("kadri@example.ee"))
                .isEqualTo(emailHasher.hash("kadri@example.ee"));
    }

    @Test
    void theHashIsCaseInsensitive_preservingWhatCitextUsedToGive() {
        // The column was CITEXT, so "Kadri@Example.EE" and "kadri@example.ee"
        // were one account. Losing that would let the same person register
        // twice by changing capitalisation.
        assertThat(emailHasher.hash("Kadri@Example.EE"))
                .isEqualTo(emailHasher.hash("  kadri@example.ee  "));
    }

    @Test
    void differentAddressesHashDifferently() {
        assertThat(emailHasher.hash("a@example.ee")).isNotEqualTo(emailHasher.hash("b@example.ee"));
    }

    @Test
    void theHashDoesNotContainTheAddress() {
        // It is an HMAC, not an encoding — the point is that a stolen dump
        // cannot be read back into an address list.
        assertThat(emailHasher.hash("kadri@example.ee"))
                .doesNotContain("kadri").doesNotContain("example");
    }

    @Test
    void anAbsentAddressHasNoHash() {
        // An anonymised account must free its address: a lingering hash would
        // keep the unique index occupied and permanently ban that person from
        // signing up again.
        assertThat(emailHasher.hash(null)).isNull();
        assertThat(emailHasher.hash("  ")).isNull();
    }

    // ── as stored ────────────────────────────────────────────────────────

    @Test
    void theStoredEmailIsCiphertext_andTheHashIsPopulated() throws Exception {
        String email = "blind-" + UUID.randomUUID() + "@test.ee";
        register(email);

        var row = jdbc.queryForMap(
                "SELECT email, email_hash FROM users WHERE email_hash = ?",
                emailHasher.hash(email));

        assertThat((String) row.get("email")).startsWith("enc:v1:");
        assertThat((String) row.get("email")).doesNotContain(email);
        assertThat((String) row.get("email_hash")).isEqualTo(emailHasher.hash(email));
    }

    @Test
    void noPlaintextAddressSurvivesAnywhereInTheUsersTable() {
        // Catches a row written by a path that forgot to encrypt.
        Integer plaintext = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email IS NOT NULL AND email NOT LIKE 'enc:v1:%'",
                Integer.class);
        assertThat(plaintext).isZero();
    }

    // ── and it still works ───────────────────────────────────────────────

    @Test
    void loginStillWorksThroughTheBlindIndex() throws Exception {
        String email = "blind-login-" + UUID.randomUUID() + "@test.ee";
        register(email);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void loginIsStillCaseInsensitive() throws Exception {
        String email = "Blind-Case-" + UUID.randomUUID() + "@Test.EE";
        register(email);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toLowerCase() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void theSameAddressCannotRegisterTwice() throws Exception {
        String email = "blind-dupe-" + UUID.randomUUID() + "@test.ee";
        register(email);

        // Uniqueness moved from the email column to the hash; if that move were
        // incomplete, duplicate accounts would silently become possible.
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new RegisterRequest(email, PASSWORD, "Duplicate", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void theRepositoryFindsAnAccountByItsHash() throws Exception {
        String email = "blind-find-" + UUID.randomUUID() + "@test.ee";
        register(email);

        assertThat(users.findActiveByEmailHash(emailHasher.hash(email))).isPresent();
        assertThat(users.findActiveByEmailHash(emailHasher.hash("nobody@test.ee"))).isEmpty();
    }
}
