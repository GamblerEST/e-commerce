package ee.shop.catalog.web;

import ee.shop.common.security.EmailHasher;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin catalogue CRUD.
 *
 * The most important test here is productCreatedByAdmin_isActuallyBuyable:
 * a product without an inventory row looks completely fine in the API and
 * the catalogue, and only fails when a real customer tries to buy it. That
 * is precisely the kind of gap that ships unnoticed, so it gets an
 * end-to-end test rather than an assertion about a row existing.
 */
@Tag("api")
@AutoConfigureMockMvc
class AdminCatalogControllerTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    // ── fixtures ─────────────────────────────────────────────────────────

    private record Admin(String token) {}

    /** ADMIN plus an enabled second factor — @AdminOnly needs both. */
    private Admin admin() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@test.ee";
        String token = tokenFor(email);
        jdbc.update("UPDATE users SET role = 'ADMIN', totp_secret_enc = 'test-secret', "
                + "totp_enabled_at = now() WHERE email_hash = ?", emailHasher.hash(email));
        return new Admin(token);
    }

    private String customerToken() throws Exception {
        return tokenFor("customer-" + UUID.randomUUID() + "@test.ee");
    }

    private String adminWithoutTwoFactorToken() throws Exception {
        String email = "admin-no2fa-" + UUID.randomUUID() + "@test.ee";
        String token = tokenFor(email);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_hash = ?", emailHasher.hash(email));
        return token;
    }

    private String tokenFor(String email) throws Exception {
        var req = new RegisterRequest(email, "correct-horse-battery", "Catalog Tester", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    private String productBody(String slug, String vatCategory, String price, int stock) {
        return """
                {"name":"Test Product","slug":"%s","description":"A product",
                 "brand":"Acme","price":%s,"currency":"EUR","vatCategory":"%s",
                 "stockQuantity":%d}
                """.formatted(slug, price, vatCategory, stock);
    }

    private String uniqueSlug() {
        return "widget-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── authorization ────────────────────────────────────────────────────

    @Test
    void catalogueWrites_areRejectedForPlainCustomers() throws Exception {
        mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(uniqueSlug(), "STANDARD", "10.00", 5)))
                .andExpect(status().isForbidden());
    }

    @Test
    void catalogueWrites_areRejectedForAnAdminWithoutTwoFactor() throws Exception {
        mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + adminWithoutTwoFactorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(uniqueSlug(), "STANDARD", "10.00", 5)))
                .andExpect(status().isForbidden());
    }

    // ── the invariant that fails silently ────────────────────────────────

    /**
     * A product with no inventory row is in the catalogue but unsellable —
     * the cart reads zero available, checkout's reserve() matches no row.
     * Nothing about the create response would reveal that, so this buys it.
     */
    @Test
    void productCreatedByAdmin_isActuallyBuyable() throws Exception {
        Admin admin = admin();
        String slug = uniqueSlug();

        MvcResult created = mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(slug, "STANDARD", "49.99", 7)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID productId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("id").asText());

        Integer onHand = jdbc.queryForObject(
                "SELECT on_hand FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(onHand).as("stock must be tracked, not just declared on the product row").isEqualTo(7);

        // And prove it end to end: a real customer can put it in a cart and check out.
        String shopper = customerToken();
        Cookie csrf = mvc.perform(get("/api/cart")).andReturn().getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();

        mvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + shopper)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isOk());

        var billing = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", null, null, null, null);
        mvc.perform(post("/api/checkout")
                        .header("Authorization", "Bearer " + shopper)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isCreated());
    }

    // ── validation that prevents a late failure ──────────────────────────

    /**
     * TaxProperties.rateFor() throws on an unknown category, and it runs at
     * CHECKOUT. Rejecting it here is the difference between a 400 the admin
     * sees immediately and a 500 the first customer sees.
     */
    @Test
    void unknownVatCategory_isRejectedAtCreation_notAtSomeonesCheckout() throws Exception {
        mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(uniqueSlug(), "MADE_UP_RATE", "10.00", 5)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void priceWithMoreThanTwoDecimals_isRejected() throws Exception {
        mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(uniqueSlug(), "STANDARD", "19.999", 5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("must have at most 2 decimal places"));
    }

    @Test
    void duplicateSlug_isConflict() throws Exception {
        Admin admin = admin();
        String slug = uniqueSlug();

        mvc.perform(post("/api/admin/products").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(slug, "STANDARD", "10.00", 5)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/admin/products").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(slug, "STANDARD", "12.00", 5)))
                .andExpect(status().isConflict());
    }

    @Test
    void slugWithSpacesOrUppercase_isRejected() throws Exception {
        mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody("Not A Slug", "STANDARD", "10.00", 5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.slug").exists());
    }

    // ── soft delete ──────────────────────────────────────────────────────

    @Test
    void deletingAProduct_hidesItButKeepsTheRow() throws Exception {
        Admin admin = admin();
        String slug = uniqueSlug();
        MvcResult created = mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(slug, "STANDARD", "10.00", 5)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID productId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("id").asText());

        mvc.perform(delete("/api/admin/products/" + productId)
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isNoContent());

        // The row survives — order_lines reference product_id and are immutable.
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?", Integer.class, productId);
        assertThat(rows).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM products WHERE id = ?",
                Boolean.class, productId)).isTrue();

        // ...but it is gone from the shop floor.
        mvc.perform(get("/api/products/" + slug)).andExpect(status().isNotFound());
    }

    // ── categories ───────────────────────────────────────────────────────

    @Test
    void category_cannotBecomeItsOwnAncestor() throws Exception {
        Admin admin = admin();
        UUID parentId = createCategory(admin, "Parent", uniqueSlug());
        UUID childId = createCategoryUnder(admin, parentId, "Child", uniqueSlug());

        // Try to move the parent underneath its own child — a cycle.
        mvc.perform(put("/api/admin/categories/" + parentId)
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + childId + "\",\"name\":\"Parent\",\"slug\":\"" + uniqueSlug() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingACategoryThatStillHasProducts_isRefused() throws Exception {
        Admin admin = admin();
        UUID categoryId = createCategory(admin, "Occupied", uniqueSlug());

        mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"In Category","slug":"%s","price":10.00,"currency":"EUR",
                                 "vatCategory":"STANDARD","stockQuantity":1,"categoryId":"%s"}
                                """.formatted(uniqueSlug(), categoryId)))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/admin/categories/" + categoryId)
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isConflict());
    }

    /**
     * A request carrying a field the DTO does not declare is refused, not quietly
     * stripped. Here the field is a privilege claim, the kind a mass-assignment
     * attack would send. The product must not be created.
     */
    @Test
    void unknownFieldInARequest_isRefused_andNothingIsCreated() throws Exception {
        Admin admin = admin();
        String slug = uniqueSlug();
        String body = productBody(slug, "STANDARD", "10.00", 1)
                .replaceFirst("\\{", "{\"role\":\"ADMIN\",");

        mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE slug = ?", Integer.class, slug))
                .isZero();
    }

    // ── attributes (the filters' facts) ──────────────────────────────────

    private UUID createProduct(Admin admin) throws Exception {
        MvcResult created = mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody(uniqueSlug(), "STANDARD", "10.00", 1)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private MvcResult putAttributes(Admin admin, UUID id, String body, int expectedStatus) throws Exception {
        return mvc.perform(put("/api/admin/products/" + id + "/attributes")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    @Test
    void attributes_areSavedNormalised_andReadBack() throws Exception {
        Admin admin = admin();
        UUID id = createProduct(admin);

        putAttributes(admin, id,
                "{\"  Colour \":[\"Red\",\" Blue \",\"Red\",\"\"],\"size\":[\"M\"]}", 200);

        mvc.perform(get("/api/admin/products/" + id + "/attributes")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.colour.length()").value(2))
                .andExpect(jsonPath("$.colour[0]").value("Blue"))
                .andExpect(jsonPath("$.colour[1]").value("Red"))
                .andExpect(jsonPath("$.size[0]").value("M"))
                .andExpect(jsonPath("$['  Colour ']").doesNotExist());
    }

    /** A set, not a patch: whatever is not sent is gone, so nothing stale can linger. */
    @Test
    void savingAttributes_replacesTheWholeSet() throws Exception {
        Admin admin = admin();
        UUID id = createProduct(admin);
        putAttributes(admin, id, "{\"colour\":[\"red\"],\"size\":[\"M\"]}", 200);

        putAttributes(admin, id, "{\"material\":[\"wool\"]}", 200);

        assertThat(jdbc.queryForList("SELECT attribute_name FROM product_attributes WHERE product_id = ?",
                String.class, id)).containsExactly("material");
    }

    @Test
    void savingNoAttributes_clearsThem() throws Exception {
        Admin admin = admin();
        UUID id = createProduct(admin);
        putAttributes(admin, id, "{\"colour\":[\"red\"]}", 200);

        putAttributes(admin, id, "{}", 200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_attributes WHERE product_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void attributes_appearInThePublicFilters() throws Exception {
        Admin admin = admin();
        UUID id = createProduct(admin);
        putAttributes(admin, id, "{\"colour\":[\"ultramarine-" + id.toString().substring(0, 6) + "\"]}", 200);
        String slug = jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, id);

        mvc.perform(get("/api/products/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.colour[0]")
                        .value("ultramarine-" + id.toString().substring(0, 6)));
    }

    @Test
    void attributes_withABlankNameOrAnOverlongValue_areRejected() throws Exception {
        Admin admin = admin();
        UUID id = createProduct(admin);

        putAttributes(admin, id, "{\"   \":[\"x\"]}", 400);
        putAttributes(admin, id, "{\"colour\":[\"" + "x".repeat(121) + "\"]}", 400);
        putAttributes(admin, id, "{\"" + "n".repeat(61) + "\":[\"x\"]}", 400);
    }

    @Test
    void attributes_areAdminOnly_andAnUnknownProductIsNotFound() throws Exception {
        UUID id = createProduct(admin());

        mvc.perform(put("/api/admin/products/" + id + "/attributes")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        putAttributes(admin(), UUID.randomUUID(), "{}", 404);
    }

    // ── dimensions survive an edit ───────────────────────────────────────

    /**
     * A PUT replaces every field it carries, so an admin form that does not load and
     * resend the dimensions wipes them on every edit. The admin view must carry them
     * so the form can.
     */
    @Test
    void dimensions_areReturnedToTheAdminForm_andRoundTripThroughAnEdit() throws Exception {
        Admin admin = admin();
        String slug = uniqueSlug();
        MvcResult created = mvc.perform(post("/api/admin/products")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Boxed","slug":"%s","price":10.00,"currency":"EUR",
                                 "vatCategory":"STANDARD","stockQuantity":1,
                                 "weightKg":1.250,"lengthCm":30.00,"widthCm":20.50,"heightCm":10.00}
                                """.formatted(slug)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.weightKg").value(1.25))
                .andExpect(jsonPath("$.lengthCm").value(30.0))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // What the form now sends back on Save: the same values, a changed name.
        mvc.perform(put("/api/admin/products/" + id)
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Boxed renamed","slug":"%s","price":10.00,"currency":"EUR",
                                 "vatCategory":"STANDARD","stockQuantity":1,
                                 "weightKg":1.250,"lengthCm":30.00,"widthCm":20.50,"heightCm":10.00}
                                """.formatted(slug)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widthCm").value(20.5))
                .andExpect(jsonPath("$.heightCm").value(10.0));
    }

    private UUID createCategory(Admin admin, String name, String slug) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/categories")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createCategoryUnder(Admin admin, UUID parentId, String name, String slug) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/categories")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + parentId + "\",\"name\":\"" + name
                                + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
