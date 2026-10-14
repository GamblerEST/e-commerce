package ee.shop.catalog.service;

import ee.shop.catalog.model.ProductImage;
import ee.shop.catalog.repository.ProductImageRepository;
import ee.shop.catalog.web.dto.FacetsResponse;
import ee.shop.catalog.web.dto.ProductSearchResponse;
import ee.shop.catalog.web.dto.ProductSort;
import ee.shop.catalog.web.dto.ProductSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hand-built dynamic SQL rather than a Specification/Criteria API — the
 * combination of an arbitrary number of EAV attribute filters, three
 * different possible sort columns, and keyset pagination whose comparison
 * operator flips with sort direction doesn't fit a derived-query or
 * Querydsl-style abstraction cleanly. Every value is still bound as a named
 * parameter — never string-concatenated — so this is not an injection risk.
 */
@Service
public class ProductSearchService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductImageRepository images;

    public ProductSearchService(NamedParameterJdbcTemplate jdbc, ProductImageRepository images) {
        this.jdbc = jdbc;
        this.images = images;
    }

    public ProductSearchResponse search(UUID categoryId, String brand, BigDecimal minPrice, BigDecimal maxPrice,
                                         String q, Map<String, String> attributes,
                                         ProductSort sort, String cursor, int limit) {

        ProductSort effectiveSort = (sort == ProductSort.RELEVANCE && (q == null || q.isBlank()))
                ? ProductSort.RATING_DESC // relevance is meaningless without a search term
                : sort;

        Filter filter = buildFilter(categoryId, brand, minPrice, maxPrice, q, attributes);

        // Total match count so the frontend can render every page number
        // upfront (1, 2, 3, …) instead of only discovering "page 2 exists"
        // after actually fetching page 1's cursor. This is a plain COUNT(*)
        // over the SAME filters, no cursor/LIMIT involved — a different
        // query shape from the keyset-paginated fetch below, and one that
        // doesn't reintroduce the expensive-deep-OFFSET problem keyset
        // pagination exists to avoid: counting matching
        // rows is cheap; skipping N of them isn't.
        String countSql = filter.cteSql() + "SELECT count(*) FROM products p WHERE p.deleted_at IS NULL" + filter.whereSql();
        long totalCount = jdbc.queryForObject(countSql, filter.params(), Long.class);

        StringBuilder sql = new StringBuilder(filter.cteSql());
        sql.append("""
                SELECT p.id, p.name, p.slug, p.brand, p.price, p.currency,
                       p.stock_quantity, p.avg_rating, p.review_count,
                       %s AS sort_value
                  FROM products p
                 WHERE p.deleted_at IS NULL
                """.formatted(sortValueExpression(effectiveSort)));
        sql.append(filter.whereSql());

        MapSqlParameterSource params = filter.params();

        String comparisonOperator = isDescending(effectiveSort) ? "<" : ">";
        if (cursor != null && !cursor.isBlank()) {
            ProductCursor decoded = ProductCursor.decode(cursor);
            sql.append(" AND (").append(sortValueExpression(effectiveSort)).append(", p.id) ")
               .append(comparisonOperator).append(" (:cursorSortValue, :cursorId)\n");
            params.addValue("cursorSortValue", new BigDecimal(decoded.sortValue()));
            params.addValue("cursorId", decoded.id());
        }

        sql.append(" ORDER BY sort_value ").append(isDescending(effectiveSort) ? "DESC" : "ASC")
           .append(", p.id ").append(isDescending(effectiveSort) ? "DESC" : "ASC").append('\n');

        // Over-fetch by one to know whether a next page exists without a
        // separate COUNT query.
        sql.append(" LIMIT :limit\n");
        params.addValue("limit", limit + 1);

        List<Row> rows = jdbc.query(sql.toString(), params, (rs, rowNum) -> new Row(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("brand"),
                rs.getBigDecimal("price"),
                rs.getString("currency"),
                rs.getInt("stock_quantity"),
                rs.getBigDecimal("avg_rating"),
                rs.getInt("review_count"),
                rs.getBigDecimal("sort_value")
        ));

        boolean hasMore = rows.size() > limit;
        List<Row> page = hasMore ? rows.subList(0, limit) : rows;

        List<UUID> pageIds = page.stream().map(Row::id).toList();
        Map<UUID, String> thumbnails = new HashMap<>();
        for (ProductImage img : images.findByProductIdInOrderByDisplayOrder(pageIds)) {
            // The small rendition, not the original (V21). A listing page shows
            // 20+ of these at ~200px; sending full-size originals is the single
            // biggest thing that makes a catalogue page slow on mobile data.
            // Falls back to the original when no rendition exists.
            thumbnails.putIfAbsent(img.getProductId(), "/" + img.getThumbnailKeyOrOriginal());
        }

        List<ProductSummary> items = page.stream()
                .map(r -> new ProductSummary(r.id, r.name, r.slug, r.brand, r.price, r.currency,
                        r.stockQuantity, r.avgRating, r.reviewCount, thumbnails.get(r.id)))
                .toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            Row last = page.get(page.size() - 1);
            nextCursor = new ProductCursor(last.sortValue.toPlainString(), last.id).encode();
        }

        return new ProductSearchResponse(items, nextCursor, totalCount);
    }

    /**
     * What's actually available to filter by within a category (or the
     * whole catalog, with no categoryId) — deliberately NOT narrowed by any
     * other active filter (brand/price/attributes), so picking one facet
     * never hides the others. A fuller cross-narrowing facet engine (where
     * choosing a brand also shrinks the attribute-value lists) is more than
     * this catalog's size warrants right now.
     */
    public FacetsResponse getFacets(UUID categoryId) {
        Filter filter = buildFilter(categoryId, null, null, null, null, null);

        String brandSql = filter.cteSql()
                + "SELECT DISTINCT p.brand FROM products p WHERE p.deleted_at IS NULL AND p.brand IS NOT NULL"
                + filter.whereSql() + " ORDER BY p.brand";
        List<String> brands = jdbc.queryForList(brandSql, filter.params(), String.class);

        String attrSql = filter.cteSql()
                + "SELECT DISTINCT pa.attribute_name, pa.attribute_value FROM product_attributes pa "
                + "JOIN products p ON p.id = pa.product_id WHERE p.deleted_at IS NULL"
                + filter.whereSql() + " ORDER BY pa.attribute_name, pa.attribute_value";

        List<Map.Entry<String, String>> attrRows = jdbc.query(attrSql, filter.params(),
                (rs, rowNum) -> Map.entry(rs.getString("attribute_name"), rs.getString("attribute_value")));

        Map<String, List<String>> attributes = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> row : attrRows) {
            attributes.computeIfAbsent(row.getKey(), k -> new java.util.ArrayList<>()).add(row.getValue());
        }

        return new FacetsResponse(brands, attributes);
    }

    /**
     * Everything the SELECT and the COUNT queries share: the category
     * subtree CTE (if any) and every WHERE condition except the cursor —
     * a page boundary is meaningless to "how many rows match in total".
     */
    private record Filter(String cteSql, String whereSql, MapSqlParameterSource params) {}

    private Filter buildFilter(UUID categoryId, String brand, BigDecimal minPrice, BigDecimal maxPrice,
                                String q, Map<String, String> attributes) {
        MapSqlParameterSource params = new MapSqlParameterSource();

        // Products are assigned to LEAF categories only (see V4 seed data) —
        // categories are a self-referential tree, so filtering by a parent
        // (e.g. "Electronics") with a plain equality match would always
        // return zero rows even though its subcategories (Laptops, Phones)
        // have products. Walk the subtree instead.
        String cteSql = "";
        if (categoryId != null) {
            cteSql = """
                    WITH RECURSIVE category_subtree AS (
                        SELECT id FROM categories WHERE id = :categoryId
                        UNION ALL
                        SELECT c.id FROM categories c
                        JOIN category_subtree cs ON c.parent_id = cs.id
                    )
                    """;
        }

        StringBuilder where = new StringBuilder();
        if (q != null && !q.isBlank()) {
            where.append(" AND (p.name ILIKE '%' || :q || '%' OR similarity(p.name, :q) > 0.2)\n");
            params.addValue("q", q);
        }
        if (categoryId != null) {
            where.append(" AND p.category_id IN (SELECT id FROM category_subtree)\n");
            params.addValue("categoryId", categoryId);
        }
        if (brand != null && !brand.isBlank()) {
            where.append(" AND p.brand = :brand\n");
            params.addValue("brand", brand);
        }
        if (minPrice != null) {
            where.append(" AND p.price >= :minPrice\n");
            params.addValue("minPrice", minPrice);
        }
        if (maxPrice != null) {
            where.append(" AND p.price <= :maxPrice\n");
            params.addValue("maxPrice", maxPrice);
        }
        if (attributes != null) {
            int i = 0;
            for (Map.Entry<String, String> attr : attributes.entrySet()) {
                String nameParam = "attrName" + i;
                String valueParam = "attrValue" + i;
                where.append(" AND EXISTS (SELECT 1 FROM product_attributes pa WHERE pa.product_id = p.id ")
                     .append(" AND pa.attribute_name = :").append(nameParam)
                     .append(" AND pa.attribute_value = :").append(valueParam).append(")\n");
                params.addValue(nameParam, attr.getKey());
                params.addValue(valueParam, attr.getValue());
                i++;
            }
        }

        return new Filter(cteSql, where.toString(), params);
    }

    /**
     * Cast to ::numeric, NOT ::text — sorting text lexicographically breaks
     * numeric ordering the moment values have different digit counts
     * ("129.00" sorts before "1099.00" as text, since '2' > '0'). Every sort
     * column ends up NUMERIC-comparable this way, which is also what makes a
     * single generic BigDecimal cursor type work below instead of one branch
     * per sort.
     */
    private static String sortValueExpression(ProductSort sort) {
        return switch (sort) {
            case RELEVANCE -> "similarity(p.name, :q)::numeric";
            case PRICE_ASC, PRICE_DESC -> "p.price";
            case RATING_DESC -> "p.avg_rating";
        };
    }

    private static boolean isDescending(ProductSort sort) {
        return sort == ProductSort.PRICE_DESC || sort == ProductSort.RATING_DESC || sort == ProductSort.RELEVANCE;
    }

    private record Row(UUID id, String name, String slug, String brand, BigDecimal price, String currency,
                        int stockQuantity, BigDecimal avgRating, int reviewCount, BigDecimal sortValue) {}
}
