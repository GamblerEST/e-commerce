package ee.shop.catalog.web;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bulk product upload, JSON and CSV.
 *
 * The property under test is ALL-OR-NOTHING. A partial import is the failure
 * mode that actually hurts: nobody can tell by looking whether rows 1-40 of a
 * 200-row file landed, and re-running the file then duplicates them. So the
 * important tests here are the ones where the import is REJECTED — proving
 * the good rows in the same file were not written either.
 */
@Tag("api")
@Tag("flow")
@AutoConfigureMockMvc
class ProductBulkImportTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String adminToken() throws Exception {
        String email = "bulk-admin-" + UUID.randomUUID() + "@test.ee";
        var req = new RegisterRequest(email, "correct-horse-battery", "Bulk Admin", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        jdbc.update("UPDATE users SET role = 'ADMIN', totp_secret_enc = 'test-secret', "
                + "totp_enabled_at = now() WHERE email_hash = ?", emailHasher.hash(email));
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    private String slug() {
        return "bulk-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private boolean exists(String slug) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM products WHERE slug = ?", Integer.class, slug);
        return count != null && count > 0;
    }

    // ── JSON ─────────────────────────────────────────────────────────────

    @Test
    void jsonImport_createsEveryProduct_withStockTracked() throws Exception {
        String a = slug();
        String b = slug();

        mvc.perform(post("/api/admin/products/bulk")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"name":"Bulk One","slug":"%s","price":10.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":4},
                                  {"name":"Bulk Two","slug":"%s","price":20.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":7}
                                ]
                                """.formatted(a, b)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(2));

        assertThat(exists(a)).isTrue();
        assertThat(exists(b)).isTrue();

        // Imported products must be sellable, not just present — same trap as
        // single-product creation (AdminCatalogControllerTest).
        Integer onHand = jdbc.queryForObject(
                "SELECT i.on_hand FROM inventory i JOIN products p ON p.id = i.product_id WHERE p.slug = ?",
                Integer.class, a);
        assertThat(onHand).isEqualTo(4);
    }

    /**
     * The core guarantee: one bad row means NOTHING is written, including the
     * valid rows that came before it.
     */
    @Test
    void oneBadRow_rejectsTheWholeFile_andWritesNothing() throws Exception {
        String good = slug();
        String alsoGood = slug();

        mvc.perform(post("/api/admin/products/bulk")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"name":"Fine","slug":"%s","price":10.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":1},
                                  {"name":"","slug":"Not A Slug","price":-5,"currency":"eur",
                                   "vatCategory":"STANDARD","stockQuantity":1},
                                  {"name":"Also fine","slug":"%s","price":30.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":1}
                                ]
                                """.formatted(good, alsoGood)))
                .andExpect(status().isBadRequest());

        assertThat(exists(good)).as("a valid row before the bad one must not survive").isFalse();
        assertThat(exists(alsoGood)).as("nor one after it").isFalse();
    }

    /** Fixing a spreadsheet one error per upload is not a workflow anyone finishes. */
    @Test
    void aRejectedImport_reportsEveryProblem_notJustTheFirst() throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/products/bulk")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"name":"","slug":"bad slug one","price":10.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":1},
                                  {"name":"","slug":"bad slug two","price":10.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":1}
                                ]
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"row\":1").contains("\"row\":2");
    }

    /**
     * Two rows claiming the same slug. The database's unique index would catch
     * it eventually, but only partway through the write and with a message
     * naming neither row — so it is caught up front instead.
     */
    @Test
    void duplicateSlugsWithinOneFile_areRejectedNamingBothRows() throws Exception {
        String duplicated = slug();

        MvcResult result = mvc.perform(post("/api/admin/products/bulk")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"name":"First","slug":"%s","price":10.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":1},
                                  {"name":"Second","slug":"%s","price":20.00,"currency":"EUR",
                                   "vatCategory":"STANDARD","stockQuantity":1}
                                ]
                                """.formatted(duplicated, duplicated)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("duplicate of row 1");
        assertThat(exists(duplicated)).isFalse();
    }

    // ── CSV ──────────────────────────────────────────────────────────────

    @Test
    void csvImport_createsProducts() throws Exception {
        String a = slug();
        String csv = "name,slug,price,currency,vatCategory,stockQuantity\n"
                + "CSV One," + a + ",12.50,EUR,STANDARD,3\n";

        mvc.perform(multipart("/api/admin/products/bulk/csv")
                        .file(new MockMultipartFile("file", "products.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1));

        assertThat(exists(a)).isTrue();
    }

    /**
     * The reason this uses a real CSV parser rather than split(","): a quoted
     * description containing a comma is valid CSV, and splitting on commas
     * would shift every later column by one — writing the description into
     * `price` and failing, or worse, succeeding with wrong data.
     */
    @Test
    void csvWithCommasAndQuotesInsideFields_isParsedCorrectly() throws Exception {
        String a = slug();
        String csv = "name,slug,description,price,currency,vatCategory,stockQuantity\n"
                + "\"Widget, large\"," + a + ",\"Sturdy, reliable, and \"\"quoted\"\"\",15.00,EUR,STANDARD,2\n";

        mvc.perform(multipart("/api/admin/products/bulk/csv")
                        .file(new MockMultipartFile("file", "products.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isCreated());

        String name = jdbc.queryForObject("SELECT name FROM products WHERE slug = ?", String.class, a);
        String description = jdbc.queryForObject(
                "SELECT description FROM products WHERE slug = ?", String.class, a);
        assertThat(name).isEqualTo("Widget, large");
        assertThat(description).isEqualTo("Sturdy, reliable, and \"quoted\"");
    }

    @Test
    void csvWithANonNumericPrice_isRejectedWithoutWritingAnything() throws Exception {
        String a = slug();
        // "12,50" — the European decimal comma, a very common spreadsheet export.
        String csv = "name,slug,price,currency,vatCategory,stockQuantity\n"
                + "Bad Price," + a + ",\"12,50\",EUR,STANDARD,1\n";

        mvc.perform(multipart("/api/admin/products/bulk/csv")
                        .file(new MockMultipartFile("file", "products.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest());

        assertThat(exists(a)).isFalse();
    }

    // ── authorization ────────────────────────────────────────────────────

    @Test
    void bulkImport_requiresAnAdmin() throws Exception {
        String email = "bulk-customer-" + UUID.randomUUID() + "@test.ee";
        var req = new RegisterRequest(email, "correct-horse-battery", "Customer", null);
        MvcResult reg = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = json.readValue(reg.getResponse().getContentAsString(), AuthResponse.class).accessToken();

        mvc.perform(post("/api/admin/products/bulk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }
}
