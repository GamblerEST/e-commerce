package ee.shop.catalog.repository;

import ee.shop.catalog.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySlugAndDeletedAtIsNull(String slug);

    /** Live products by name, for the listing page served to crawlers. */
    List<Product> findByDeletedAtIsNullOrderByNameAsc(Pageable page);

    /** Guards category deletion — a category still holding live products must not vanish. */
    boolean existsByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    /**
     * Type-ahead suggestions via pg_trgm similarity (V3__catalog.sql's GIN
     * trigram index on name) rather than a separate search engine — fast
     * substring/fuzzy matching without new infrastructure.
     */
    @Query(value = """
            SELECT id, name, slug
              FROM products
             WHERE deleted_at IS NULL
               AND (name ILIKE '%' || :q || '%' OR similarity(name, :q) > 0.2)
             ORDER BY similarity(name, :q) DESC, name
             LIMIT :limit
            """, nativeQuery = true)
    List<SuggestionRow> suggest(@Param("q") String q, @Param("limit") int limit);

    /**
     * Admin catalogue listing, including soft-deleted rows when asked.
     *
     * Native rather than a derived query name because the deleted filter is
     * CONDITIONAL — `deleted_at IS NULL OR :includeDeleted` cannot be
     * expressed as a Spring Data method name without writing two methods and
     * branching at the call site.
     */
    @Query(value = """
            SELECT *
              FROM products
             WHERE (:includeDeleted OR deleted_at IS NULL)
               AND (CAST(:q AS text) IS NULL OR name ILIKE '%' || CAST(:q AS text) || '%')
             ORDER BY deleted_at NULLS FIRST, name
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Product> findForAdmin(@Param("q") String q,
                               @Param("includeDeleted") boolean includeDeleted,
                               @Param("limit") int limit,
                               @Param("offset") int offset);

    interface SuggestionRow {
        UUID getId();
        String getName();
        String getSlug();
    }
}
