package ee.shop.catalog.service;

import ee.shop.catalog.model.Category;
import ee.shop.catalog.model.Product;
import ee.shop.catalog.model.ProductAttribute;
import ee.shop.catalog.repository.CategoryRepository;
import ee.shop.catalog.repository.ProductAttributeRepository;
import ee.shop.catalog.repository.ProductRepository;
import ee.shop.catalog.web.dto.AdminCategoryRequest;
import ee.shop.catalog.web.dto.AdminProductRequest;
import ee.shop.common.error.AppException;
import ee.shop.config.TaxProperties;
import ee.shop.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/**
 * Admin-side catalogue writes. Authorization is on the
 * controller (@AdminOnly); this class assumes the caller is already allowed
 * and concerns itself with the invariants a valid catalogue needs.
 *
 * Three of those are worth naming, because each one fails LATE and quietly
 * if it isn't checked here:
 *
 * 1. Every product gets an inventory row. Without one it is silently
 *    unsellable — the cart reads zero available and checkout's reserve()
 *    matches no row — and nothing surfaces that until a customer tries to
 *    buy it. V10 backfilled the products that existed then; nothing created
 *    rows for products added afterwards, so this is where that gap closes.
 * 2. The VAT category must be one the tax configuration actually knows.
 *    TaxProperties.rateFor() THROWS on an unknown key, and it is called at
 *    checkout — so an unvalidated value here produces a product that looks
 *    fine in the catalogue and 500s the moment someone tries to order it.
 * 3. A category cannot become its own ancestor. Categories are
 *    self-referential, and a cycle turns every tree walk into an infinite
 *    loop rather than an error.
 */
@Service
public class AdminCatalogService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final InventoryRepository inventory;
    private final TaxProperties tax;
    private final ProductAttributeRepository attributes;

    public AdminCatalogService(ProductRepository products, CategoryRepository categories,
                                InventoryRepository inventory, TaxProperties tax,
                                ProductAttributeRepository attributes) {
        this.products = products;
        this.categories = categories;
        this.inventory = inventory;
        this.tax = tax;
        this.attributes = attributes;
    }

    // ── product attributes (the facets: colour, size, material...) ────────

    static final int MAX_ATTRIBUTE_NAMES = 20;
    static final int MAX_VALUES_PER_ATTRIBUTE = 30;
    static final int MAX_NAME_LENGTH = 60;
    static final int MAX_VALUE_LENGTH = 120;

    @Transactional(readOnly = true)
    public Map<String, List<String>> attributesOf(UUID productId) {
        liveProduct(productId);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ProductAttribute a : attributes.findByProductIdOrderByAttributeName(productId)) {
            result.computeIfAbsent(a.getAttributeName(), k -> new java.util.ArrayList<>())
                    .add(a.getAttributeValue());
        }
        // The table has no order of its own for values, so without this the form could
        // show them in a different order on every load.
        result.values().forEach(values -> values.sort(String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Replaces the product's whole attribute set. A set, not a patch, so what the
     * admin sees is exactly what is stored and there is no way to leave a stale
     * value behind. Names are stored lowercase and trimmed (the seeded data and the
     * product page both assume it, and "Color" beside "color" would split one
     * filter in two); values keep their case; duplicates collapse.
     */
    @Transactional
    public Map<String, List<String>> replaceAttributes(UUID productId, Map<String, List<String>> requested) {
        liveProduct(productId);
        Map<String, Set<String>> clean = normaliseAttributes(requested);

        attributes.deleteByProductId(productId);
        attributes.flush();
        for (Map.Entry<String, Set<String>> e : clean.entrySet()) {
            for (String value : e.getValue()) {
                attributes.save(new ProductAttribute(productId, e.getKey(), value));
            }
        }
        return attributesOf(productId);
    }

    static Map<String, Set<String>> normaliseAttributes(Map<String, List<String>> requested) {
        Map<String, Set<String>> clean = new LinkedHashMap<>();
        if (requested == null) {
            return clean;
        }
        for (Map.Entry<String, List<String>> e : requested.entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw AppException.badRequest("An attribute needs a name");
            }
            if (name.length() > MAX_NAME_LENGTH) {
                throw AppException.badRequest("Attribute names can be at most " + MAX_NAME_LENGTH + " characters");
            }
            Set<String> values = clean.computeIfAbsent(name, k -> new LinkedHashSet<>());
            for (String raw : e.getValue() == null ? List.<String>of() : e.getValue()) {
                String value = raw == null ? "" : raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (value.length() > MAX_VALUE_LENGTH) {
                    throw AppException.badRequest(
                            "Attribute values can be at most " + MAX_VALUE_LENGTH + " characters");
                }
                values.add(value);
            }
            if (values.size() > MAX_VALUES_PER_ATTRIBUTE) {
                throw AppException.badRequest(
                        "An attribute can have at most " + MAX_VALUES_PER_ATTRIBUTE + " values");
            }
        }
        // An attribute with no values left is dropped, not stored as an empty facet.
        clean.values().removeIf(Set::isEmpty);
        if (clean.size() > MAX_ATTRIBUTE_NAMES) {
            throw AppException.badRequest("A product can have at most " + MAX_ATTRIBUTE_NAMES + " attributes");
        }
        return clean;
    }

    // ── products ──────────────────────────────────────────────────────────

    /**
     * Admin catalogue listing. Unlike the public search this can include
     * soft-deleted rows, because restoring one requires finding it first.
     *
     * Plain ILIKE rather than the trigram similarity the shopper-facing search
     * uses: an admin looking for a specific product knows its name and wants
     * an exact substring match, not fuzzy ranking that buries the row they
     * typed in favour of something that merely looks like it.
     */
    @Transactional(readOnly = true)
    public List<Product> listProducts(String q, boolean includeDeleted, int page, int size) {
        String term = (q == null || q.isBlank()) ? null : q.trim();
        return products.findForAdmin(term, includeDeleted, size, page * size);
    }

    @Transactional
    public Product createProduct(AdminProductRequest req) {
        validate(req, null);

        Product product = products.save(new Product(req.toSpec()));
        // See the class javadoc, invariant 1 — a product without this row is
        // in the catalogue but cannot be bought.
        inventory.createIfAbsent(product.getId(), req.stockQuantity());
        return product;
    }

    @Transactional
    public Product updateProduct(UUID id, AdminProductRequest req) {
        Product product = liveProduct(id);
        validate(req, id);
        product.update(req.toSpec());
        // Restores stock tracking for a product that predates this endpoint
        // (or was created before the inventory row existed); a no-op otherwise.
        inventory.createIfAbsent(product.getId(), req.stockQuantity());
        return products.save(product);
    }

    /**
     * Soft delete. A hard delete would have to cascade into order_lines,
     * which are immutable financial records (DECISIONS.md §1/§2) — the
     * product row has to survive so an old invoice can still link back to
     * "this is what you bought".
     */
    @Transactional
    public void deleteProduct(UUID id) {
        Product product = liveProduct(id);
        product.softDelete();
        products.save(product);
    }

    @Transactional
    public Product restoreProduct(UUID id) {
        Product product = products.findById(id)
                .orElseThrow(() -> AppException.notFound("Product not found"));
        product.restore();
        return products.save(product);
    }

    private void validate(AdminProductRequest req, UUID selfId) {
        if (!tax.rates().containsKey(req.vatCategory())) {
            // 400 here rather than a 500 at someone's checkout — see invariant 2.
            throw AppException.badRequest("Unknown VAT category: " + req.vatCategory()
                    + " (configured: " + tax.rates().keySet() + ")");
        }
        if (req.categoryId() != null && categories.findById(req.categoryId()).isEmpty()) {
            throw AppException.badRequest("Category not found: " + req.categoryId());
        }
        products.findBySlugAndDeletedAtIsNull(req.slug())
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw AppException.conflict("A product with slug '" + req.slug() + "' already exists");
                });
    }

    private Product liveProduct(UUID id) {
        return products.findById(id)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> AppException.notFound("Product not found"));
    }

    // ── categories ────────────────────────────────────────────────────────

    @Transactional
    public Category createCategory(AdminCategoryRequest req) {
        requireUniqueSlug(req.slug(), null);
        requireParentExists(req.parentId());
        Category created = new Category(req.parentId(), req.name(), req.slug());
        created.promote(req.featured() != null && req.featured(), req.tagline());
        return categories.save(created);
    }

    @Transactional
    public Category updateCategory(UUID id, AdminCategoryRequest req) {
        Category category = categories.findById(id)
                .orElseThrow(() -> AppException.notFound("Category not found"));
        requireUniqueSlug(req.slug(), id);
        requireParentExists(req.parentId());
        requireNoCycle(id, req.parentId());
        category.update(req.parentId(), req.name(), req.slug());
        category.promote(req.featured() != null && req.featured(), req.tagline());
        return categories.save(category);
    }

    /**
     * Refused while anything still points at it. Categories have no soft
     * delete, so removing one with children or products would either orphan
     * them or fail on a foreign key deep inside the transaction — better to
     * say which, up front.
     */
    @Transactional
    public void deleteCategory(UUID id) {
        Category category = categories.findById(id)
                .orElseThrow(() -> AppException.notFound("Category not found"));
        if (!categories.findByParentId(id).isEmpty()) {
            throw AppException.conflict("Move or delete this category's subcategories first");
        }
        if (products.existsByCategoryIdAndDeletedAtIsNull(id)) {
            throw AppException.conflict("Move or delete this category's products first");
        }
        categories.delete(category);
    }

    private void requireUniqueSlug(String slug, UUID selfId) {
        categories.findBySlug(slug)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw AppException.conflict("A category with slug '" + slug + "' already exists");
                });
    }

    private void requireParentExists(UUID parentId) {
        if (parentId != null && categories.findById(parentId).isEmpty()) {
            throw AppException.badRequest("Parent category not found: " + parentId);
        }
    }

    /** Walks up from the proposed parent; if we meet ourselves, the move would create a loop. */
    private void requireNoCycle(UUID categoryId, UUID proposedParentId) {
        Set<UUID> seen = new HashSet<>();
        UUID cursor = proposedParentId;
        while (cursor != null) {
            if (cursor.equals(categoryId)) {
                throw AppException.badRequest("A category cannot be moved beneath itself");
            }
            if (!seen.add(cursor)) {
                // Pre-existing cycle in the data — stop rather than spin forever.
                break;
            }
            cursor = categories.findById(cursor).map(Category::getParentId).orElse(null);
        }
    }
}
