package ee.shop.catalog.web;

import ee.shop.catalog.model.Category;
import ee.shop.catalog.model.Product;
import ee.shop.catalog.service.AdminCatalogService;
import ee.shop.catalog.service.ProductImportService;
import ee.shop.catalog.web.dto.AdminCategoryRequest;
import ee.shop.catalog.web.dto.AdminProductRequest;
import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.common.security.AdminOnly;
import ee.shop.identity.model.User;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Catalogue management. @AdminOnly on the class, so every method inherits
 * both halves of the rule — ADMIN role AND 2FA actually enabled.
 *
 * Deliberately NOT merged into CatalogController: that one is entirely
 * public unauthenticated reads, and mixing "anyone may call this" with
 * "only a 2FA admin may call this" in one class is how an endpoint
 * eventually lands on the wrong side of the line during a refactor.
 *
 * Every mutation is audited. Who changed a price, and when, is exactly the
 * question nobody can answer after the fact unless it was recorded at the
 * time (audit_log is append-only for this reason).
 */
@RestController
@RequestMapping("/api/admin")
@AdminOnly
public class AdminCatalogController {

    private final AdminCatalogService admin;
    private final ProductImportService imports;
    private final AuditService audit;

    public AdminCatalogController(AdminCatalogService admin, ProductImportService imports, AuditService audit) {
        this.admin = admin;
        this.imports = imports;
        this.audit = audit;
    }

    // ── products ──────────────────────────────────────────────────────────

    /**
     * The catalogue as an ADMIN sees it — including soft-deleted rows.
     *
     * The public search endpoint cannot serve this screen: it filters
     * `deleted_at IS NULL`, which means a deleted product becomes invisible
     * and therefore impossible to find in order to restore it. Restore existed
     * as an endpoint with no way to reach it from a UI.
     */
    @GetMapping("/products")
    @Operation(summary = "List products for administration, optionally including deleted ones")
    public List<ProductAdminView> listProducts(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return admin.listProducts(q, includeDeleted, Math.max(page, 0),
                        Math.min(Math.max(size, 1), 200)).stream()
                .map(ProductAdminView::of)
                .toList();
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductAdminView createProduct(@Valid @RequestBody AdminProductRequest req,
                                           Authentication auth, HttpServletRequest http) {
        Product product = admin.createProduct(req);
        record(auth, http, "admin.product.created", product.getId());
        return ProductAdminView.of(product);
    }

    @PutMapping("/products/{id}")
    public ProductAdminView updateProduct(@PathVariable UUID id,
                                           @Valid @RequestBody AdminProductRequest req,
                                           Authentication auth, HttpServletRequest http) {
        Product product = admin.updateProduct(id, req);
        record(auth, http, "admin.product.updated", id);
        return ProductAdminView.of(product);
    }

    @GetMapping("/products/{id}/attributes")
    public java.util.Map<String, List<String>> attributes(@PathVariable UUID id) {
        return admin.attributesOf(id);
    }

    /** Replaces the whole set — see AdminCatalogService.replaceAttributes. */
    @PutMapping("/products/{id}/attributes")
    public java.util.Map<String, List<String>> replaceAttributes(
            @PathVariable UUID id, @RequestBody java.util.Map<String, List<String>> attributes,
            Authentication auth, HttpServletRequest http) {
        java.util.Map<String, List<String>> saved = admin.replaceAttributes(id, attributes);
        record(auth, http, "admin.product.attributes_updated", id);
        return saved;
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(@PathVariable UUID id, Authentication auth, HttpServletRequest http) {
        admin.deleteProduct(id);
        record(auth, http, "admin.product.deleted", id);
    }

    @PostMapping("/products/{id}/restore")
    public ProductAdminView restoreProduct(@PathVariable UUID id, Authentication auth, HttpServletRequest http) {
        Product product = admin.restoreProduct(id);
        record(auth, http, "admin.product.restored", id);
        return ProductAdminView.of(product);
    }

    // ── bulk upload ───────────────────────────────────────────────────────

    /**
     * All-or-nothing: every row is validated before any row is written, and
     * the response lists EVERY problem rather than the first one — see
     * ProductImportService for why a half-imported catalogue is worse than a
     * rejected one.
     */
    @PostMapping("/products/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductImportService.ImportResult bulkJson(@RequestBody List<AdminProductRequest> rows,
                                                       Authentication auth, HttpServletRequest http) {
        ProductImportService.ImportResult result = imports.importAll(rows);
        recordBulk(auth, http, result.imported(), "json");
        return result;
    }

    @PostMapping("/products/bulk/csv")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductImportService.ImportResult bulkCsv(@RequestParam MultipartFile file,
                                                      Authentication auth, HttpServletRequest http) throws IOException {
        if (file.isEmpty()) {
            throw AppException.badRequest("File is empty");
        }
        ProductImportService.ImportResult result = imports.importAll(imports.parseCsv(file.getInputStream()));
        recordBulk(auth, http, result.imported(), "csv");
        return result;
    }

    private void recordBulk(Authentication auth, HttpServletRequest http, int count, String format) {
        User user = (User) auth.getPrincipal();
        audit.record("admin.product.bulk_imported", user.getId(), count + " products (" + format + ")",
                http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    // ── categories ────────────────────────────────────────────────────────

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryAdminView createCategory(@Valid @RequestBody AdminCategoryRequest req,
                                             Authentication auth, HttpServletRequest http) {
        Category category = admin.createCategory(req);
        record(auth, http, "admin.category.created", category.getId());
        return CategoryAdminView.of(category);
    }

    @PutMapping("/categories/{id}")
    public CategoryAdminView updateCategory(@PathVariable UUID id,
                                             @Valid @RequestBody AdminCategoryRequest req,
                                             Authentication auth, HttpServletRequest http) {
        Category category = admin.updateCategory(id, req);
        record(auth, http, "admin.category.updated", id);
        return CategoryAdminView.of(category);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID id, Authentication auth, HttpServletRequest http) {
        admin.deleteCategory(id);
        record(auth, http, "admin.category.deleted", id);
    }

    private void record(Authentication auth, HttpServletRequest http, String action, UUID targetId) {
        User user = (User) auth.getPrincipal();
        audit.record(action, user.getId(), targetId.toString(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    /** Admin view of a product — includes deletedAt, which the public DTOs deliberately hide. */
    public record ProductAdminView(UUID id, String name, String slug, java.math.BigDecimal price,
                                    String currency, String vatCategory, int stockQuantity,
                                    UUID categoryId, boolean deleted, boolean featured,
                                    String brand, String description,
                                    java.math.BigDecimal weightKg, java.math.BigDecimal lengthCm,
                                    java.math.BigDecimal widthCm, java.math.BigDecimal heightCm) {
        static ProductAdminView of(Product p) {
            return new ProductAdminView(p.getId(), p.getName(), p.getSlug(), p.getPrice(),
                    p.getCurrency(), p.getVatCategory(), p.getStockQuantity(),
                    p.getCategoryId(), p.isDeleted(), p.isFeatured(),
                    // brand/description/dimensions are included so the admin edit
                    // form can be populated from the list without a second round
                    // trip per row — and so an edit cannot silently blank a field
                    // the form never loaded (a PUT replaces every field it carries).
                    p.getBrand(), p.getDescription(),
                    p.getWeightKg(), p.getLengthCm(), p.getWidthCm(), p.getHeightCm());
        }
    }

    public record CategoryAdminView(UUID id, UUID parentId, String name, String slug,
                                     boolean featured, String tagline) {
        static CategoryAdminView of(Category c) {
            return new CategoryAdminView(c.getId(), c.getParentId(), c.getName(), c.getSlug(),
                    c.isFeatured(), c.getTagline());
        }
    }
}
