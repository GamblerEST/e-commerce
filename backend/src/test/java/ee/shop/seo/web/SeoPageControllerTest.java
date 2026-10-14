package ee.shop.seo.web;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public pages as a crawler receives them: the built app, with this page's head,
 * structured data and readable content already in the HTML.
 *
 * Every request here is unauthenticated, which is also the proof that the
 * security allowlist matches these paths.
 */
@Tag("api")
@Tag("security")
@AutoConfigureMockMvc
class SeoPageControllerTest extends IntegrationTest {

    /** app.frontend-url as the tests see it. */
    private static final String SITE = "http://localhost:5173";

    private static final Pattern STRUCTURED_DATA = Pattern.compile(
            "<script data-page-meta type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String page(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** A category of this test's own; fixtures must not add rows to seeded data other tests read. */
    private UUID ownCategory() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, parent_id, name, slug) VALUES (?, NULL, 'SEO Test Fixtures', ?)",
                id, "seo-fixtures-" + id.toString().substring(0, 8));
        return id;
    }

    private String insertProduct(String name, String brand, String description, String price, int stock) {
        UUID id = UUID.randomUUID();
        String slug = "seo-" + id.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO products (id, category_id, name, slug, description, brand, price,
                                      currency, vat_category, stock_quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric, 'EUR', 'STANDARD', ?)
                """, id, ownCategory(), name, slug, description, brand, price, stock);
        return slug;
    }

    private List<JsonNode> structuredData(String html) {
        List<JsonNode> nodes = new ArrayList<>();
        Matcher m = STRUCTURED_DATA.matcher(html);
        while (m.find()) {
            nodes.add(json.readTree(m.group(1)));
        }
        return nodes;
    }

    private JsonNode productData(String html) {
        return structuredData(html).stream()
                .filter(n -> "Product".equals(n.path("@type").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Product structured data on the page"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void theHomePageIsTheBuiltAppWithItsOwnHeadAndReadableContent() throws Exception {
        String html = page(get("/"), 200);

        assertThat(count(html, "<title>")).isEqualTo(1);
        assertThat(count(html, "name=\"description\"")).isEqualTo(1);
        assertThat(html).contains("<title>Estonian Shop — home goods, books and electronics</title>");
        assertThat(html).contains("<link data-page-meta rel=\"canonical\" href=\"" + SITE + "/\" />");
        assertThat(html).contains("<div id=\"root\"><main><h1>Everyday things, chosen well and priced honestly.</h1>");
        assertThat(html).contains("<h2>Featured right now</h2>");
        // The build's own assets stay exactly where they were.
        assertThat(html).contains("<script type=\"module\" crossorigin src=\"/assets/index-test.js\"></script>");
    }

    @Test
    void aProductPageCarriesItsOwnTitleDescriptionCanonicalAndStructuredData() throws Exception {
        String slug = insertProduct("Rannamõisa Mug", "Nordic Clay", "Hand-thrown stoneware.", "12.90", 3);

        String html = page(get("/products/" + slug), 200);

        assertThat(html).contains("<title>Rannamõisa Mug — Estonian Shop</title>");
        // Exactly what ProductDetailPage.buildMetaDescription produces for this product.
        assertThat(html).contains("<meta name=\"description\" content=\"Hand-thrown stoneware. Buy Rannamõisa Mug "
                + "by Nordic Clay from Estonian Shop — VAT included, tracked delivery across Estonia.\" />");
        assertThat(html).contains("rel=\"canonical\" href=\"" + SITE + "/products/" + slug + "\"");
        assertThat(html).contains("<h1>Rannamõisa Mug</h1>");

        JsonNode product = productData(html);
        assertThat(product.path("name").asText()).isEqualTo("Rannamõisa Mug");
        assertThat(product.path("brand").path("name").asText()).isEqualTo("Nordic Clay");
        assertThat(product.path("offers").path("price").asText()).isEqualTo("12.90");
        assertThat(product.path("offers").path("priceCurrency").asText()).isEqualTo("EUR");
        assertThat(product.path("offers").path("availability").asText()).isEqualTo("https://schema.org/InStock");
    }

    /**
     * Staff write product text, and a page that let it out of its tag or out of
     * the structured data would be stored XSS on every visit.
     */
    @Test
    void productTextCannotBreakOutOfTheHtmlOrTheStructuredData() throws Exception {
        String slug = insertProduct(
                "Mug</title><script>alert(1)</script>",
                "\"><img src=x onerror=alert(2)>",
                "</script><script>alert(3)</script>",
                "9.90", 1);

        String html = page(get("/products/" + slug), 200);

        assertThat(html).doesNotContain("<script>alert").doesNotContain("<img src=x");
        assertThat(count(html, "<title>")).isEqualTo(1);
        // Every </script> on the page closes a script the page meant to open.
        assertThat(count(html, "</script>")).isEqualTo(count(html, "<script"));
        assertThat(productData(html).path("name").asText()).isEqualTo("Mug</title><script>alert(1)</script>");
    }

    @Test
    void anUnknownProductIsARealNotFoundThatAsksNotToBeIndexed() throws Exception {
        String html = page(get("/products/no-such-product-" + UUID.randomUUID()), 404);

        assertThat(html).contains("<meta data-page-meta name=\"robots\" content=\"noindex,nofollow\" />");
        assertThat(html).contains("<h1>Product not found</h1>");
        assertThat(html).doesNotContain("rel=\"canonical\"");
    }

    @Test
    void aProductTakenOffSaleIsNotFound() throws Exception {
        String slug = insertProduct("Retired Mug", "", "", "5.00", 0);
        jdbc.update("UPDATE products SET deleted_at = now() WHERE slug = ?", slug);

        page(get("/products/" + slug), 404);
    }

    @Test
    void theListingLinksToLiveProductsAndIsIndexable() throws Exception {
        String html = page(get("/products"), 200);

        assertThat(html).contains("<title>All products — Estonian Shop</title>");
        assertThat(html).contains("<h1>All products</h1>").contains("<a href=\"/products/");
        assertThat(html).doesNotContain("name=\"robots\"");
    }

    @Test
    void aSearchIsNotIndexedAndShowsTheTermOnlyEscaped() throws Exception {
        String html = page(get("/products").param("q", "<b>bold</b>"), 200);

        assertThat(html).contains("name=\"robots\" content=\"noindex,nofollow\"");
        assertThat(html).doesNotContain("<b>bold</b>");
        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void theCompanyAndLegalPagesHaveTheirOwnTitles() throws Exception {
        assertThat(page(get("/about"), 200))
                .contains("<title>About Estonian Shop</title>").contains("<h1>About Estonian Shop</h1>");
        assertThat(page(get("/contacts"), 200)).contains("<title>Contact Estonian Shop</title>");
        assertThat(page(get("/privacy"), 200)).contains("<title>Privacy policy — Estonian Shop</title>");
        assertThat(page(get("/sales-conditions"), 200)).contains("<title>Sales conditions — Estonian Shop</title>");
    }
}
