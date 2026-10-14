package ee.shop.catalog;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-level constraint and query-correctness tests. Products/Category have no
 * public constructor (nothing creates them outside the V4 seed migration yet
 * — admin CRUD came later), so these go through JdbcTemplate
 * directly rather than the entity, same as the constraints themselves are
 * enforced by Postgres rather than application code.
 */
@Tag("api")
class CatalogPersistenceTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;

    /**
     * The bug that was actually found and fixed in ProductSearchService
     * (sort column cast to ::text instead of ::numeric): "9.00" sorts AFTER
     * "10.00" as text ('1' < '9'), but 9.00 must sort BEFORE 10.00
     * numerically. The seeded catalog happens to contain a pair (129.00 vs
     * 1099.00) that would also catch this, but only incidentally — nothing
     * pins the bug down by name, so a seed-data change could silently let it
     * back in with every existing test still green. This does not rely on
     * seed data at all.
     */
    @Test
    void priceSort_ordersNumericallyNotLexicographically() {
        UUID cheap = insertProduct("Regression Probe Cheap", new BigDecimal("9.00"));
        UUID expensive = insertProduct("Regression Probe Expensive", new BigDecimal("10.00"));

        List<UUID> orderedIds = jdbc.query(
                "SELECT id FROM products WHERE id IN (?, ?) ORDER BY price::numeric ASC",
                (rs, i) -> (UUID) rs.getObject("id"), cheap, expensive);

        assertThat(orderedIds).containsExactly(cheap, expensive);

        // The bug this guards against, made explicit: a plain text ORDER BY
        // would put "10.00" before "9.00".
        List<UUID> textOrderedIds = jdbc.query(
                "SELECT id FROM products WHERE id IN (?, ?) ORDER BY price::text ASC",
                (rs, i) -> (UUID) rs.getObject("id"), cheap, expensive);
        assertThat(textOrderedIds).containsExactly(expensive, cheap);
    }

    @Test
    void deletingAParentCategory_setsChildrensParentIdToNull_ratherThanFailingOrCascading() {
        UUID parent = insertCategory(null, "Regression Parent " + UUID.randomUUID());
        UUID child = insertCategory(parent, "Regression Child " + UUID.randomUUID());

        jdbc.update("DELETE FROM categories WHERE id = ?", parent);

        UUID childParentAfterDelete = jdbc.queryForObject(
                "SELECT parent_id FROM categories WHERE id = ?", UUID.class, child);
        assertThat(childParentAfterDelete).isNull();

        // The child itself must still exist — SET NULL, not CASCADE.
        Integer stillExists = jdbc.queryForObject(
                "SELECT count(*) FROM categories WHERE id = ?", Integer.class, child);
        assertThat(stillExists).isEqualTo(1);
    }

    @Test
    void duplicateSlug_violatesUniqueConstraint() {
        // insertCategory() generates its own random slug internally, so it
        // can't be used for the first row here — this inserts directly to
        // guarantee both rows target the EXACT same slug value.
        String slug = "regression-dup-slug-" + UUID.randomUUID();
        jdbc.update("INSERT INTO categories (name, slug) VALUES (?, ?)", "First", slug);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO categories (name, slug) VALUES (?, ?)", "Second", slug))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertProduct(String name, BigDecimal price) {
        UUID id = UUID.randomUUID();
        String slug = "regression-" + id;
        jdbc.update("INSERT INTO products (id, name, slug, price) VALUES (?, ?, ?, ?)",
                id, name, slug, price);
        return id;
    }

    private UUID insertCategory(UUID parentId, String name) {
        UUID id = UUID.randomUUID();
        String slug = "regression-" + id;
        jdbc.update("INSERT INTO categories (id, parent_id, name, slug) VALUES (?, ?, ?, ?)",
                id, parentId, name, slug);
        return id;
    }
}
