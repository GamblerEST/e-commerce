package ee.shop.catalog.web;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The home page showcases featured products and collections.
 *
 * The interesting cases here are not "does it return rows". They are the ways
 * a shop window silently shows the wrong thing: a retired product still on the
 * front page, an empty collection tile linking nowhere, or a count that
 * disagrees with the listing behind it.
 */
@Tag("api")
@AutoConfigureMockMvc
class HomeControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ee.shop.catalog.repository.HomeRepository home;

    /**
     * A category created FOR this test class.
     *
     * Originally this reused a seeded top-level category, which quietly broke
     * CatalogControllerTest: these tests share one database with no per-test
     * rollback, so fixture products inserted under a seeded category showed up
     * in that test's assertion about which brands live under it. A fixture
     * must not add rows to data another test reads.
     */
    private UUID ownCategory() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO categories (id, parent_id, name, slug)
                VALUES (?, NULL, 'Home Test Fixtures', ?)
                """, id, "home-fixtures-" + UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private UUID insertProduct(UUID categoryId, String slug, boolean featured, String rating) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, category_id, name, slug, description, brand, price,
                                      currency, vat_category, stock_quantity, featured)
                VALUES (?, ?, ?, ?, '', 'Acme', 10.00, 'EUR', 'STANDARD', 5, ?)
                """, id, categoryId, "Home " + slug, slug, featured);
        // avg_rating is trigger-maintained from reviews and not insertable through
        // JPA, so it is set directly here to control ordering deterministically.
        jdbc.update("UPDATE products SET avg_rating = ?::numeric WHERE id = ?", rating, id);
        return id;
    }

    // ── the shop window is not empty on a fresh clone ────────────────────

    /**
     * V18 seeds featured flags precisely so anyone following the README's
     * quick start does not land on a blank home page. If that seed ever stops
     * matching the catalogue seed, this fails.
     */
    @Test
    void home_isPopulatedOnAFreshDatabase() throws Exception {
        mvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").isNotEmpty())
                .andExpect(jsonPath("$.collections").isNotEmpty());
    }

    @Test
    void home_isPublic_noAuthenticationRequired() throws Exception {
        // The landing page cannot 401 an anonymous visitor. SecurityConfig
        // allowlists it; this proves the allowlist actually matches the path,
        // which a pattern typo would otherwise only reveal in a browser.
        mvc.perform(get("/api/home")).andExpect(status().isOk());
    }

    // ── what must never appear ───────────────────────────────────────────

    /**
     * A soft-deleted product is off the shop floor. If the featured query
     * forgot `deleted_at IS NULL`, the front page would keep advertising it
     * and every click would 404 on the detail page.
     */
    @Test
    void featured_excludesSoftDeletedProducts() throws Exception {
        UUID id = insertProduct(ownCategory(), "retired-" + UUID.randomUUID(), true, "5.00");
        jdbc.update("UPDATE products SET deleted_at = now() WHERE id = ?", id);

        String body = mvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(id.toString());
    }

    @Test
    void featured_excludesProductsNotFlagged() throws Exception {
        UUID id = insertProduct(ownCategory(), "ordinary-" + UUID.randomUUID(), false, "5.00");

        String body = mvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(id.toString());
    }

    /**
     * An empty collection tile is worse than a missing one — it promises a
     * range and delivers a "no products" page.
     */
    @Test
    void collections_excludeCategoriesWithNoLiveProducts() throws Exception {
        UUID emptyCategory = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO categories (id, parent_id, name, slug, featured)
                VALUES (?, NULL, 'Empty Collection', ?, true)
                """, emptyCategory, "empty-" + UUID.randomUUID().toString().substring(0, 8));

        String body = mvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(emptyCategory.toString());
    }

    // ── the counts have to be true ───────────────────────────────────────

    /**
     * The tile says "N products". If that number counted only rows sitting
     * directly on the top-level category, every realistically-shaped
     * catalogue (products on SUBcategories) would advertise 0 or a wrong
     * total. This asserts the count follows the same subtree the listing does.
     */
    @Test
    void collectionCount_includesProductsOnChildCategories() throws Exception {
        UUID parent = UUID.randomUUID();
        String parentSlug = "parent-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO categories (id, parent_id, name, slug, featured)
                VALUES (?, NULL, 'Parent Collection', ?, true)
                """, parent, parentSlug);

        UUID child = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO categories (id, parent_id, name, slug)
                VALUES (?, ?, 'Child', ?)
                """, child, parent, "child-" + UUID.randomUUID().toString().substring(0, 8));

        insertProduct(child, "kid-a-" + UUID.randomUUID(), false, "3.00");
        insertProduct(child, "kid-b-" + UUID.randomUUID(), false, "4.00");

        // Asserted against the repository, not GET /api/home, on purpose. The
        // endpoint caps the shop window at MAX_COLLECTIONS and orders by name,
        // so whether this fixture appears there depends on how many categories
        // the seed happens to promote and what they are called — which would
        // make this a test of the cap, not of the count. The cap is covered by
        // the endpoint tests above; the subtree arithmetic is covered here.
        //
        // Un-featuring the seeded categories to force this one into the window
        // was the alternative, and was rejected: these tests share one database
        // with no per-test rollback, so that would silently break whichever
        // sibling test ran next.
        var row = home.findFeaturedCollections(500).stream()
                .filter(c -> c.getId().equals(parent))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "promoted collection with live products was not returned at all"));

        assertThat(row.getProductCount()).isEqualTo(2);
    }

    // ── ordering ─────────────────────────────────────────────────────────

    /**
     * Curation picks WHICH products are featured; availability and then rating
     * order them.
     *
     * The ordering is deliberately NOT "highest rated first" overall: a
     * sold-out product is pushed below every buyable one, because the shop
     * window's most valuable space should not lead with a dead end. So the
     * contract this asserts is "in-stock before sold-out, and rating
     * descending WITHIN each of those groups" — asserting a single global
     * descent would be asserting a behaviour the query intentionally does not
     * have, and would fail the moment any featured product sells out.
     */
    @Test
    void featured_leadWithBuyableProducts_thenByRatingWithinEachGroup() throws Exception {
        String body = mvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Double> ratings = com.jayway.jsonpath.JsonPath.read(body, "$.featured[*].avgRating");
        List<Integer> stock = com.jayway.jsonpath.JsonPath.read(body, "$.featured[*].stockQuantity");

        List<Double> inStock = new java.util.ArrayList<>();
        List<Double> soldOut = new java.util.ArrayList<>();
        boolean seenSoldOut = false;
        for (int i = 0; i < ratings.size(); i++) {
            if (stock.get(i) > 0) {
                assertThat(seenSoldOut)
                        .as("a buyable product must not appear after a sold-out one")
                        .isFalse();
                inStock.add(ratings.get(i));
            } else {
                seenSoldOut = true;
                soldOut.add(ratings.get(i));
            }
        }

        assertThat(inStock).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(soldOut).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }
}
