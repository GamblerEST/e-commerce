package ee.shop.catalog.web;

import ee.shop.common.security.EmailHasher;
import tools.jackson.databind.ObjectMapper;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("api")
@Tag("security")
@AutoConfigureMockMvc
class ProductImageControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String email() {
        return "user" + UUID.randomUUID() + "@test.ee";
    }

    /**
     * JwtAuthFilter re-checks the DB role on every request rather than
     * trusting the token's role claim (see JwtAuthFilter.java:72) — so
     * flipping the role AFTER issuing a token is enough to make an
     * already-issued access token behave as an admin on the next request.
     *
     * 2FA is switched on here too, because @AdminOnly requires it: the role
     * alone no longer grants admin powers. adminWithout2fa_cannotUpload
     * below is the test for that half of the rule.
     */
    private String registerAsAdminAndGetAccessToken() throws Exception {
        String email = email();
        String accessToken = registerAndGetTokenFor(email);
        jdbc.update("UPDATE users SET role = 'ADMIN', totp_secret_enc = 'test-secret', "
                + "totp_enabled_at = now() WHERE email_hash = ?", emailHasher.hash(email));
        return accessToken;
    }

    /** An admin who never turned 2FA on — the role is there, the second factor is not. */
    private String registerAsAdminWithoutTwoFactor() throws Exception {
        String email = email();
        String accessToken = registerAndGetTokenFor(email);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_hash = ?", emailHasher.hash(email));
        return accessToken;
    }

    private String registerAndGetTokenFor(String email) throws Exception {
        var req = new RegisterRequest(email, "correct-horse-battery", "Test Admin", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    private String registerAndGetAccessToken() throws Exception {
        var req = new RegisterRequest(email(), "correct-horse-battery", "Test User", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    /**
     * ORDER BY is not decoration here. `SELECT id FROM products LIMIT 1`
     * without one lets Postgres return whichever row it reaches first, and
     * that changes as rows are inserted — so once any test in this class
     * created its own product, this helper could hand a DIFFERENT product to
     * each caller and the failure would look like an unrelated count being
     * wrong. Ordering by the earliest row pins it to the seeded catalogue.
     */
    private UUID anySeededProductId() {
        return jdbc.queryForObject(
                "SELECT id FROM products ORDER BY created_at, id LIMIT 1", UUID.class);
    }

    /** A real, tiny, ImageIO-decodable image — not just bytes labeled as one. */
    private byte[] realImageBytes(String formatName) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean wrote = ImageIO.write(image, formatName, out);
        assertThat(wrote).as("an ImageIO writer for '%s' is registered", formatName).isTrue();
        return out.toByteArray();
    }

    @Test
    void upload_asAdmin_storesFileAndInsertsRow() throws Exception {
        String accessToken = registerAsAdminAndGetAccessToken();
        // Its own product: this test counts uploads and expects exactly one,
        // which is only true if nothing else can upload to the same row.
        UUID productId = ownProductId();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", realImageBytes("jpg"));

        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(file)
                        .param("altText", "Product photo")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM product_images WHERE product_id = ? AND storage_key LIKE 'product-images/uploads/%'",
                Integer.class, productId);
        assertThat(rows).isEqualTo(1);
    }

    /**
     * The actual vulnerability this endpoint used to have: Content-Type is
     * whatever the CLIENT claims in the multipart request. Before
     * ProductImageController verified the bytes via ImageIO, this exact
     * request (garbage bytes, a trusted-looking image/jpeg label) succeeded —
     * which is how a disguised script/HTML file could have ended up served
     * back to every visitor from /product-images/uploads/.
     */
    @Test
    void upload_withValidContentTypeButGarbageBytes_isRejected() throws Exception {
        String accessToken = registerAsAdminAndGetAccessToken();
        UUID productId = anySeededProductId();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    /**
     * The JDK's built-in ImageIO has no WebP support at all — without the
     * imageio-webp plugin (pom.xml), ProductImageController's ImageIO.read()
     * verification would reject every genuinely valid WebP upload, even
     * though "image/webp" is on ALLOWED_TYPES. TwelveMonkeys only ships a
     * WebP READER, not a writer (confirmed: ImageIO.getWriterFormatNames()
     * has no "webp"), so this checks the reader is actually registered
     * rather than round-tripping a self-encoded file through the endpoint.
     */
    @Test
    void webpImageReader_isRegistered_soRealWebpUploadsWontBeWronglyRejected() {
        assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext())
                .as("a WebP ImageIO reader must be registered — see imageio-webp in pom.xml")
                .isTrue();
    }

    @Test
    void upload_asRegularCustomer_isForbidden() throws Exception {
        String accessToken = registerAndGetAccessToken();
        UUID productId = anySeededProductId();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    /**
     * The ADMIN role on its own is no longer enough — @AdminOnly also
     * requires the account to have 2FA actually enabled. This is where
     * "2FA enforced for all admin accounts" is really enforced: login
     * deliberately still succeeds for such an account (blocking it there
     * could strand the only administrator with no recovery path), but no
     * privileged action will run for it.
     */
    @Test
    void upload_asAdminWithoutTwoFactor_isForbidden() throws Exception {
        String accessToken = registerAsAdminWithoutTwoFactor();
        UUID productId = anySeededProductId();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_withoutAuthentication_isUnauthorized() throws Exception {
        UUID productId = anySeededProductId();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

        mvc.perform(multipart("/api/admin/products/" + productId + "/images").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_withDisallowedContentType_isRejected() throws Exception {
        String accessToken = registerAsAdminAndGetAccessToken();
        UUID productId = anySeededProductId();
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_forUnknownProduct_isNotFound() throws Exception {
        String accessToken = registerAsAdminAndGetAccessToken();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

        mvc.perform(multipart("/api/admin/products/" + UUID.randomUUID() + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    // ── renditions (V21) ─────────────────────────────────────────────────

    /**
     * A product created FOR one test.
     *
     * anySeededProductId() hands every caller the SAME row, and these tests
     * share one database with no per-test rollback — so a test that uploads an
     * image to it changes what other tests see. upload_asAdmin_storesFileAnd
     * InsertsRow counts uploads on its product and expects exactly one, which
     * is only true while nothing else uploads there too.
     */
    private UUID ownProductId() {
        UUID id = UUID.randomUUID();
        String slug = "img-fixture-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO products (id, name, slug, description, brand, price, currency,
                                      vat_category, stock_quantity)
                VALUES (?, 'Image Fixture', ?, '', 'Acme', 10.00, 'EUR', 'STANDARD', 1)
                """, id, slug);
        return id;
    }

    /** A source big enough that both renditions have something to shrink. */
    private byte[] largeImageBytes() throws IOException {
        BufferedImage image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(0, 0, 1600, 1200);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /**
     * The requirement is that images are STORED and SERVED in multiple sizes.
     * Asserting the columns are populated proves storage; asserting the files
     * decode at the right dimensions proves the renditions are real images and
     * not empty placeholders.
     */
    @Test
    void uploadingALargeImage_storesThumbnailAndMediumRenditions() throws Exception {
        UUID productId = ownProductId();
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.jpg", "image/jpeg", largeImageBytes());

        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + registerAsAdminAndGetAccessToken()))
                .andExpect(status().isCreated());

        var row = jdbc.queryForMap(
                "SELECT storage_key, thumbnail_key, medium_key FROM product_images "
                        + "WHERE product_id = ? ORDER BY created_at DESC LIMIT 1", productId);

        assertThat((String) row.get("thumbnail_key")).isNotNull().endsWith("-thumb.jpg");
        assertThat((String) row.get("medium_key")).isNotNull().endsWith("-medium.jpg");

        java.nio.file.Path dir = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "shop-test-product-images");
        BufferedImage thumb = ImageIO.read(dir.resolve(
                java.nio.file.Path.of((String) row.get("thumbnail_key")).getFileName().toString()).toFile());
        BufferedImage medium = ImageIO.read(dir.resolve(
                java.nio.file.Path.of((String) row.get("medium_key")).getFileName().toString()).toFile());

        assertThat(thumb).isNotNull();
        assertThat(medium).isNotNull();
        // Long edge capped, aspect ratio preserved (1600x1200 is 4:3).
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(400);
        assertThat(Math.max(medium.getWidth(), medium.getHeight())).isEqualTo(1000);
        assertThat(thumb.getWidth()).isEqualTo(400);
        assertThat(thumb.getHeight()).isEqualTo(300);
    }

    /**
     * An image already smaller than the targets gets no renditions — upscaling
     * costs bytes and adds no detail. The API must still return usable URLs,
     * which is what the fallback to the original is for.
     */
    @Test
    void aTinyImage_getsNoRenditions_andStillServesUsableUrls() throws Exception {
        UUID productId = ownProductId();
        MockMultipartFile file = new MockMultipartFile(
                "file", "tiny.jpg", "image/jpeg", realImageBytes("jpg"));

        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + registerAsAdminAndGetAccessToken()))
                .andExpect(status().isCreated());

        var row = jdbc.queryForMap(
                "SELECT thumbnail_key, medium_key FROM product_images "
                        + "WHERE product_id = ? ORDER BY created_at DESC LIMIT 1", productId);

        assertThat(row.get("thumbnail_key")).isNull();
        assertThat(row.get("medium_key")).isNull();

        // The product page must still get three resolvable URLs, never a null.
        String slug = jdbc.queryForObject(
                "SELECT slug FROM products WHERE id = ?", String.class, productId);
        mvc.perform(get("/api/products/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].url").isNotEmpty())
                .andExpect(jsonPath("$.images[0].mediumUrl").isNotEmpty())
                .andExpect(jsonPath("$.images[0].thumbnailUrl").isNotEmpty());
    }

    // ── listing and removing ─────────────────────────────────────────────

    private void uploadTo(UUID productId, String token, String altText) throws Exception {
        mvc.perform(multipart("/api/admin/products/" + productId + "/images")
                        .file(new MockMultipartFile("file", "p.jpg", "image/jpeg", realImageBytes("jpg")))
                        .param("altText", altText)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    @Test
    void anAdminCanSeeAProductsImages_inOrder() throws Exception {
        String token = registerAsAdminAndGetAccessToken();
        UUID productId = ownProductId();
        uploadTo(productId, token, "first");
        uploadTo(productId, token, "second");

        mvc.perform(get("/api/admin/products/" + productId + "/images")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].altText").value("first"))
                .andExpect(jsonPath("$[1].altText").value("second"))
                .andExpect(jsonPath("$[0].url").value(org.hamcrest.Matchers.startsWith("/product-images/uploads/")));
    }

    @Test
    void listingImages_isForbiddenToACustomer() throws Exception {
        mvc.perform(get("/api/admin/products/" + ownProductId() + "/images")
                        .header("Authorization", "Bearer " + registerAndGetAccessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void removingAnImage_deletesTheRowAndTheFiles_andTheNextUploadStillGetsAFreeOrder() throws Exception {
        String token = registerAsAdminAndGetAccessToken();
        UUID productId = ownProductId();
        uploadTo(productId, token, "keep");
        uploadTo(productId, token, "remove");
        UUID removeId = jdbc.queryForObject(
                "SELECT id FROM product_images WHERE product_id = ? AND alt_text = 'remove'", UUID.class, productId);
        String key = jdbc.queryForObject(
                "SELECT storage_key FROM product_images WHERE id = ?", String.class, removeId);
        java.nio.file.Path file = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "shop-test-product-images", key.substring("product-images/uploads/".length()));
        assertThat(file).exists();

        mvc.perform(delete("/api/admin/products/" + productId + "/images/" + removeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id = ?", Integer.class, removeId))
                .isZero();
        assertThat(file).doesNotExist();

        uploadTo(productId, token, "later");
        Integer distinctOrders = jdbc.queryForObject(
                "SELECT count(DISTINCT display_order) FROM product_images WHERE product_id = ?",
                Integer.class, productId);
        assertThat(distinctOrders).as("no two images share a display order after a delete").isEqualTo(2);
    }

    @Test
    void anImageCannotBeRemovedThroughAnotherProductsUrl() throws Exception {
        String token = registerAsAdminAndGetAccessToken();
        UUID owner = ownProductId();
        UUID other = ownProductId();
        uploadTo(owner, token, "mine");
        UUID imageId = jdbc.queryForObject(
                "SELECT id FROM product_images WHERE product_id = ?", UUID.class, owner);

        mvc.perform(delete("/api/admin/products/" + other + "/images/" + imageId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id = ?", Integer.class, imageId))
                .isEqualTo(1);
    }

    @Test
    void removingAnImage_isForbiddenToACustomer() throws Exception {
        mvc.perform(delete("/api/admin/products/" + ownProductId() + "/images/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + registerAndGetAccessToken()))
                .andExpect(status().isForbidden());
    }
}
