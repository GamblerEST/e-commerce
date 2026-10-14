package ee.shop.catalog.web;

import ee.shop.catalog.model.Product;
import ee.shop.catalog.model.ProductAttribute;
import ee.shop.catalog.repository.CategoryRepository;
import ee.shop.catalog.repository.ProductAttributeRepository;
import ee.shop.catalog.repository.ProductImageRepository;
import ee.shop.catalog.repository.ProductRepository;
import ee.shop.catalog.service.HomeService;
import ee.shop.catalog.service.ProductSearchService;
import ee.shop.catalog.service.UnitConverter;
import ee.shop.catalog.web.dto.*;
import ee.shop.common.error.AppException;
import ee.shop.common.security.RateLimitService;
import ee.shop.identity.model.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * All GET, all public — SecurityConfig already permits
 * GET /api/products/** and /api/categories/** without authentication.
 */
@RestController
public class CatalogController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int SUGGESTION_LIMIT = 8;
    /** Free-text params bypass DTO-level @Size validation (they're plain
     *  @RequestParam strings, not bound to a validated record) — enforced
     *  manually here instead, same reasoning as the limit bounds check
     *  below: an unbounded query string reaches the DB search/trigram layer
     *  un-truncated otherwise. */
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_BRAND_LENGTH = 100;

    /** Query params that aren't facet attribute filters — everything else
     *  beginning with "attr_" is treated as an attribute name=value filter. */
    private static final String ATTR_PREFIX = "attr_";

    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ProductAttributeRepository attributes;
    private final CategoryRepository categories;
    private final ProductSearchService search;
    private final HomeService home;
    private final RateLimitService rateLimit;

    public CatalogController(ProductRepository products, ProductImageRepository images,
                              ProductAttributeRepository attributes, CategoryRepository categories,
                              ProductSearchService search, HomeService home,
                              RateLimitService rateLimit) {
        this.products = products;
        this.images = images;
        this.attributes = attributes;
        this.categories = categories;
        this.search = search;
        this.home = home;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/api/products")
    public ProductSearchResponse list(
            Authentication authentication, HttpServletRequest http,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) java.util.UUID categoryId,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "RATING_DESC") ProductSort sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam Map<String, String> allParams) {

        enforceSearchRateLimit(authentication, http);

        if (limit < 1 || limit > MAX_LIMIT) {
            throw AppException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        requireMaxLength("q", q, MAX_QUERY_LENGTH);
        requireMaxLength("brand", brand, MAX_BRAND_LENGTH);

        Map<String, String> attributeFilters = allParams.entrySet().stream()
                .filter(e -> e.getKey().startsWith(ATTR_PREFIX))
                .collect(Collectors.toMap(e -> e.getKey().substring(ATTR_PREFIX.length()), Map.Entry::getValue));

        return search.search(categoryId, brand, minPrice, maxPrice, q, attributeFilters, sort, cursor, limit);
    }

    @GetMapping("/api/products/facets")
    public FacetsResponse facets(@RequestParam(required = false) java.util.UUID categoryId) {
        return search.getFacets(categoryId);
    }

    @GetMapping("/api/products/suggest")
    public List<SuggestionItem> suggest(Authentication authentication, HttpServletRequest http,
                                         @RequestParam String q) {
        enforceSearchRateLimit(authentication, http);
        requireMaxLength("q", q, MAX_QUERY_LENGTH);

        if (q == null || q.isBlank()) {
            return List.of();
        }
        return products.suggest(q, SUGGESTION_LIMIT).stream()
                .map(row -> new SuggestionItem(row.getId(), row.getName(), row.getSlug()))
                .toList();
    }

    private void enforceSearchRateLimit(Authentication authentication, HttpServletRequest http) {
        User user = authentication != null && authentication.getPrincipal() instanceof User u ? u : null;
        rateLimit.enforceProductSearch(http.getRemoteAddr(), user != null ? user.getId() : null);
    }

    private static void requireMaxLength(String paramName, String value, int max) {
        if (value != null && value.length() > max) {
            throw AppException.badRequest(paramName + " must be at most " + max + " characters");
        }
    }

    @GetMapping("/api/products/{slug}")
    public ProductDetail detail(@PathVariable String slug) {
        Product product = products.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> AppException.notFound("Product not found"));

        CategoryRef categoryRef = product.getCategoryId() == null ? null
                : categories.findById(product.getCategoryId())
                        .map(c -> new CategoryRef(c.getId(), c.getName(), c.getSlug()))
                        .orElse(null);

        var productImages = images.findByProductIdOrderByDisplayOrder(product.getId());
        List<String> imageUrls = productImages.stream()
                .map(img -> "/" + img.getStorageKey())
                .toList();
        // Every rendition, each guaranteed to resolve — see ProductImageView.
        List<ProductImageView> imageViews = productImages.stream()
                .map(img -> new ProductImageView(
                        "/" + img.getStorageKey(),
                        "/" + img.getMediumKeyOrOriginal(),
                        "/" + img.getThumbnailKeyOrOriginal(),
                        img.getAltText()))
                .toList();

        Map<String, List<String>> attributeMap = new HashMap<>();
        for (ProductAttribute attr : attributes.findByProductIdOrderByAttributeName(product.getId())) {
            attributeMap.computeIfAbsent(attr.getAttributeName(), k -> new java.util.ArrayList<>())
                    .add(attr.getAttributeValue());
        }

        Dimensions dimensions = new Dimensions(
                product.getWeightKg(), UnitConverter.kgToLb(product.getWeightKg()),
                product.getLengthCm(), UnitConverter.cmToIn(product.getLengthCm()),
                product.getWidthCm(), UnitConverter.cmToIn(product.getWidthCm()),
                product.getHeightCm(), UnitConverter.cmToIn(product.getHeightCm())
        );

        return new ProductDetail(
                product.getId(), product.getName(), product.getSlug(), product.getDescription(), product.getBrand(),
                product.getPrice(), product.getCurrency(), product.getStockQuantity(),
                product.getAvgRating(), product.getReviewCount(),
                categoryRef, dimensions, imageUrls, imageViews, attributeMap
        );
    }

    /**
     * "You might also like" for the product detail page.
     *
     * A separate call from /api/products/{slug} on purpose: the PDP renders
     * its main content the moment the product lands, and the related strip
     * sits far below the fold. Bundling them would hold the whole page behind
     * the slower of the two queries for content nobody has scrolled to yet.
     */
    @GetMapping("/api/products/{slug}/related")
    @Operation(summary = "Related products from the same category")
    public List<ProductSummary> related(@PathVariable String slug) {
        Product product = products.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> AppException.notFound("Product not found"));
        return home.related(product.getId(), product.getCategoryId());
    }

    @GetMapping("/api/categories")
    public List<CategoryResponse> listCategories() {
        return categories.findAll().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName(), c.getSlug(), c.getParentId(),
                        c.isFeatured(), c.getTagline()))
                .toList();
    }
}
