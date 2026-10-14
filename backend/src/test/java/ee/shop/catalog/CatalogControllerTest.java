package ee.shop.catalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the seeded catalog (V4__catalog_seed.sql) — no test-specific
 * fixtures are created here, since the point is to prove the search/facet/
 * sort/pagination machinery against real, already-migrated data.
 */
@Tag("api")
@AutoConfigureMockMvc
class CatalogControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void listProducts_returnsSeededItems() throws Exception {
        JsonNode body = getJson("/api/products?limit=50");
        assertThat(body.get("items")).isNotEmpty();
    }

    @Test
    void filterByCategory_onlyReturnsThatCategorysProducts() throws Exception {
        JsonNode categories = getJson("/api/categories");
        String phonesCategoryId = null;
        for (JsonNode c : categories) {
            if (c.get("slug").asText().equals("phones")) {
                phonesCategoryId = c.get("id").asText();
            }
        }
        assertThat(phonesCategoryId).isNotNull();

        JsonNode body = getJson("/api/products?categoryId=" + phonesCategoryId + "&limit=50");
        assertThat(body.get("items")).isNotEmpty();
        for (JsonNode item : body.get("items")) {
            assertThat(item.get("brand").asText()).isIn("Aurora", "Northline");
        }
    }

    @Test
    void facets_forLeafCategory_returnsOnlyThatCategorysBrandsAndAttributes() throws Exception {
        JsonNode categories = getJson("/api/categories");
        String phonesCategoryId = null;
        for (JsonNode c : categories) {
            if (c.get("slug").asText().equals("phones")) phonesCategoryId = c.get("id").asText();
        }

        JsonNode body = getJson("/api/products/facets?categoryId=" + phonesCategoryId);

        assertThat(body.get("brands")).isNotEmpty();
        for (JsonNode brand : body.get("brands")) {
            assertThat(brand.asText()).isIn("Aurora", "Northline");
        }
        assertThat(body.get("attributes").has("color")).isTrue();
        assertThat(body.get("attributes").get("color")).isNotEmpty();
        // Clothing-only attribute values must not leak into a Phones facet.
        for (JsonNode color : body.get("attributes").get("color")) {
            assertThat(color.asText()).isNotEqualTo("Indigo"); // Weekend Denim Jacket's color
        }
    }

    @Test
    void facets_withNoCategoryId_coversTheWholeCatalog() throws Exception {
        JsonNode body = getJson("/api/products/facets");

        List<String> brands = new ArrayList<>();
        body.get("brands").forEach(b -> brands.add(b.asText()));
        // Spans multiple categories — proves it's not accidentally scoped.
        assertThat(brands).contains("Aurora", "Fieldstone", "Baltic Press");
    }

    /**
     * Products are assigned to LEAF categories only (Laptops/Phones under
     * Electronics), so filtering by the PARENT ("Electronics") must walk the
     * subtree — a plain equality match on category_id would return nothing
     * despite the parent visibly having subcategories full of products.
     */
    @Test
    void filterByParentCategory_includesProductsFromSubcategories() throws Exception {
        JsonNode categories = getJson("/api/categories");
        String electronicsId = null;
        for (JsonNode c : categories) {
            if (c.get("slug").asText().equals("electronics")) {
                electronicsId = c.get("id").asText();
            }
        }
        assertThat(electronicsId).isNotNull();

        JsonNode body = getJson("/api/products?categoryId=" + electronicsId + "&limit=50");
        assertThat(body.get("items")).isNotEmpty();
        for (JsonNode item : body.get("items")) {
            assertThat(item.get("brand").asText()).isIn("Aurora", "Northline", "Vantage");
        }
    }

    @Test
    void filterByAttribute_onlyReturnsMatchingProducts() throws Exception {
        JsonNode body = getJson("/api/products?attr_color=Navy&limit=50");
        assertThat(body.get("items")).isNotEmpty();
        for (JsonNode item : body.get("items")) {
            assertThat(item.get("name").asText()).contains("Classic Crew T-Shirt");
        }
    }

    @Test
    void filterByPriceRange_excludesProductsOutsideIt() throws Exception {
        JsonNode body = getJson("/api/products?minPrice=1000&maxPrice=2000&limit=50");
        for (JsonNode item : body.get("items")) {
            BigDecimal price = new BigDecimal(item.get("price").asText());
            assertThat(price).isBetween(new BigDecimal("1000"), new BigDecimal("2000"));
        }
    }

    @Test
    void sortByPriceAscending_returnsNonDecreasingPrices() throws Exception {
        JsonNode body = getJson("/api/products?sort=PRICE_ASC&limit=50");
        BigDecimal previous = BigDecimal.ZERO;
        for (JsonNode item : body.get("items")) {
            BigDecimal price = new BigDecimal(item.get("price").asText());
            assertThat(price).isGreaterThanOrEqualTo(previous);
            previous = price;
        }
    }

    @Test
    void sortByPriceDescending_returnsNonIncreasingPrices() throws Exception {
        JsonNode body = getJson("/api/products?sort=PRICE_DESC&limit=50");
        BigDecimal previous = new BigDecimal("999999");
        for (JsonNode item : body.get("items")) {
            BigDecimal price = new BigDecimal(item.get("price").asText());
            assertThat(price).isLessThanOrEqualTo(previous);
            previous = price;
        }
    }

    /**
     * Keyset (not OFFSET) pagination. Walks every page
     * with a small limit and asserts the union of all pages has no
     * duplicates and no gaps against a single large fetch. An OFFSET-based
     * implementation would still often pass this for STATIC data, but a
     * broken cursor (e.g. missing id tie-breaker) shows up immediately as a
     * repeated or skipped item once multiple rows share a sort value.
     */
    @Test
    void keysetPagination_visitsEveryProductExactlyOnceAcrossPages() throws Exception {
        // Scoped to ONE brand rather than the whole catalogue.
        //
        // This previously compared a `limit=100` fetch against a full paged
        // walk. MAX_LIMIT is 100, so the "expected" side silently caps at 100
        // rows while the walk keeps going — meaning the test was guaranteed to
        // fail the moment the catalogue exceeded 100 products, for reasons
        // having nothing to do with pagination. The seeded catalogue plus the
        // fixtures other test classes leave behind in this shared database
        // crossed that line.
        //
        // A brand filter bounds the universe to rows this assertion can fully
        // see, and the seeded brand below has several products sharing a price
        // — which is exactly the case that catches a cursor missing its id
        // tie-breaker, the bug this test exists to catch.
        String brand = pickBrandWithSeveralProducts();
        String base = "/api/products?sort=PRICE_ASC&brand=" + brand;

        JsonNode fullPage = getJson(base + "&limit=100");
        Set<String> expectedIds = new HashSet<>();
        fullPage.get("items").forEach(item -> expectedIds.add(item.get("id").asText()));
        assertThat(fullPage.get("totalCount").asLong())
                .as("the bounded set must fit inside one page, or this comparison is meaningless")
                .isLessThanOrEqualTo(100);

        Set<String> visitedIds = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = base + "&limit=3" + (cursor != null ? "&cursor=" + cursor : "");
            JsonNode page = getJson(url);
            for (JsonNode item : page.get("items")) {
                String id = item.get("id").asText();
                assertThat(visitedIds).as("no product should appear on two pages").doesNotContain(id);
                visitedIds.add(id);
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
            assertThat(pages).as("pagination must terminate").isLessThan(50);
        } while (cursor != null);

        assertThat(visitedIds).isEqualTo(expectedIds);
        assertThat(pages).isGreaterThan(1);
    }

    /** The seeded brand carrying the most products, so paging actually pages. */
    private String pickBrandWithSeveralProducts() throws Exception {
        JsonNode facets = getJson("/api/products/facets");
        String best = null;
        long bestCount = 0;
        for (JsonNode b : facets.get("brands")) {
            String brand = b.asText();
            long count = getJson("/api/products?limit=1&brand=" + brand).get("totalCount").asLong();
            if (count > bestCount) {
                bestCount = count;
                best = brand;
            }
        }
        assertThat(best).as("the seeded catalogue must have at least one brand").isNotNull();
        assertThat(bestCount).as("pagination needs more products than one page holds").isGreaterThan(3);
        return best;
    }

    @Test
    void suggest_returnsMatchesForPartialName() throws Exception {
        JsonNode body = getJson("/api/products/suggest?q=Aurora");
        assertThat(body).isNotEmpty();
        for (JsonNode item : body) {
            assertThat(item.get("name").asText()).containsIgnoringCase("Aurora");
        }
    }

    @Test
    void suggest_withBlankQuery_returnsEmptyList() throws Exception {
        JsonNode body = getJson("/api/products/suggest?q=");
        assertThat(body).isEmpty();
    }

    @Test
    void productDetail_includesDimensionsInBothUnitSystems() throws Exception {
        JsonNode body = getJson("/api/products/vantage-14-ultrabook");

        assertThat(body.get("name").asText()).isEqualTo("Vantage 14 Ultrabook");
        JsonNode dimensions = body.get("dimensions");
        assertThat(dimensions.get("weightKg").asDouble()).isEqualTo(1.28);
        assertThat(dimensions.get("weightLb").asDouble()).isGreaterThan(0);
        assertThat(dimensions.get("lengthCm").asDouble()).isGreaterThan(0);
        assertThat(dimensions.get("lengthIn").asDouble()).isGreaterThan(0);

        assertThat(body.get("attributes").get("color").get(0).asText()).isEqualTo("Space Gray");
        assertThat(body.get("imageUrls")).isNotEmpty();
        assertThat(body.get("category").get("slug").asText()).isEqualTo("laptops");
    }

    @Test
    void productDetail_forUnknownSlug_isNotFound() throws Exception {
        mvc.perform(get("/api/products/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void categories_includeSubcategoriesWithParentId() throws Exception {
        JsonNode categories = getJson("/api/categories");

        JsonNode electronics = null, phones = null;
        for (JsonNode c : categories) {
            if (c.get("slug").asText().equals("electronics")) electronics = c;
            if (c.get("slug").asText().equals("phones")) phones = c;
        }
        assertThat(electronics).isNotNull();
        assertThat(phones).isNotNull();
        assertThat(phones.get("parentId").asText()).isEqualTo(electronics.get("id").asText());
    }

    @Test
    void productImage_isActuallyServable() throws Exception {
        JsonNode body = getJson("/api/products/vantage-14-ultrabook");
        String imageUrl = body.get("imageUrls").get(0).asText();

        mvc.perform(get(imageUrl))
                .andExpect(status().isOk());
    }

    @Test
    void limitOutOfRange_isBadRequest() throws Exception {
        mvc.perform(get("/api/products?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products?limit=1000")).andExpect(status().isBadRequest());
    }

    /**
     * q/brand are plain @RequestParam strings, not bound to a @Size-
     * validated DTO field like every request BODY in this codebase — an
     * unbounded free-text query would otherwise reach the DB search/
     * trigram layer un-truncated. See CatalogController.MAX_QUERY_LENGTH.
     */
    @Test
    void overlongSearchQuery_isBadRequest() throws Exception {
        String tooLong = "a".repeat(201);
        mvc.perform(get("/api/products?q=" + tooLong)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/suggest?q=" + tooLong)).andExpect(status().isBadRequest());
    }

    @Test
    void overlongBrand_isBadRequest() throws Exception {
        String tooLong = "a".repeat(101);
        mvc.perform(get("/api/products?brand=" + tooLong)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownCategoryId_returnsEmptyResultsNotAnError() throws Exception {
        JsonNode body = getJson("/api/products?categoryId=" + java.util.UUID.randomUUID() + "&limit=50");
        assertThat(body.get("items")).isEmpty();
    }

    @Test
    void filterWithNoMatches_returnsEmptyItemsWithStatus200() throws Exception {
        JsonNode body = getJson("/api/products?minPrice=999999&limit=50");
        assertThat(body.get("items")).isEmpty();
    }

    @Test
    void invalidSortValue_isBadRequest() throws Exception {
        mvc.perform(get("/api/products?sort=NOT_A_REAL_SORT")).andExpect(status().isBadRequest());
    }

    /**
     * Combining a category filter with a brand filter must AND them, not OR
     * — the single-filter tests above (filterByCategory_..., filterByAttribute_...)
     * each only ever vary one facet at a time.
     */
    @Test
    void combiningCategoryAndBrandFilters_appliesBothAsAnAnd() throws Exception {
        JsonNode categories = getJson("/api/categories");
        String phonesCategoryId = null;
        for (JsonNode c : categories) {
            if (c.get("slug").asText().equals("phones")) phonesCategoryId = c.get("id").asText();
        }

        JsonNode matching = getJson("/api/products?categoryId=" + phonesCategoryId + "&brand=Aurora&limit=50");
        assertThat(matching.get("items")).isNotEmpty();
        for (JsonNode item : matching.get("items")) {
            assertThat(item.get("brand").asText()).isEqualTo("Aurora");
        }

        // Northline is a phones brand too, but not Aurora — the AND must
        // exclude it even though it matches the category alone.
        JsonNode mismatched = getJson("/api/products?categoryId=" + phonesCategoryId + "&brand=Northline&limit=50");
        for (JsonNode item : mismatched.get("items")) {
            assertThat(item.get("brand").asText()).isNotEqualTo("Aurora");
        }
    }
}
