package ee.shop.order.web;

import ee.shop.common.audit.AuditService;
import ee.shop.common.security.AdminOnly;
import ee.shop.common.security.StaffOnly;
import ee.shop.identity.model.User;
import ee.shop.order.service.AdminOrderService;
import ee.shop.order.web.dto.OrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Staff order management.
 *
 * The split mirrors AdminUserController: SUPPORT can look at orders and mark
 * them shipped (the everyday job), but only an ADMIN can move money back out
 * of the business. A refund is irreversible and has a direct financial
 * effect — it belongs with the role that owns those consequences.
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderService admin;
    private final AuditService audit;

    public AdminOrderController(AdminOrderService admin, AuditService audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping
    @StaffOnly
    public List<AdminOrderService.AdminOrderSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return admin.list(status, from, to);
    }

    @GetMapping("/{id}")
    @StaffOnly
    public OrderResponse detail(@PathVariable UUID id) {
        return admin.detail(id);
    }

    @PostMapping("/{id}/shipped")
    @StaffOnly
    public OrderResponse markShipped(@PathVariable UUID id, Authentication auth, HttpServletRequest http) {
        OrderResponse order = admin.markShipped(id);
        record(auth, http, "admin.order.shipped", id);
        return order;
    }

    @PostMapping("/{id}/delivered")
    @StaffOnly
    public OrderResponse markDelivered(@PathVariable UUID id, Authentication auth, HttpServletRequest http) {
        OrderResponse order = admin.markDelivered(id);
        record(auth, http, "admin.order.delivered", id);
        return order;
    }

    /** ADMIN only — see the class javadoc: this moves real money. */
    @PostMapping("/{id}/refund")
    @AdminOnly
    public OrderResponse refund(@PathVariable UUID id, Authentication auth, HttpServletRequest http) {
        OrderResponse order = admin.refund(id);
        record(auth, http, "admin.order.refunded", id);
        return order;
    }

    @GetMapping("/{id}/billing-documents")
    @StaffOnly
    public List<AdminOrderService.BillingDocumentView> billingDocuments(@PathVariable UUID id) {
        return admin.billingDocuments(id);
    }

    @GetMapping("/{id}/billing-documents/{documentId}/download")
    @StaffOnly
    public ResponseEntity<byte[]> downloadBillingDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        AdminOrderService.BillingFile file = admin.downloadBillingDocument(id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .body(file.bytes());
    }

    /**
     * ADMIN only, like a refund: this sends a legal document to the customer on
     * the shop's behalf. Support can view and download existing ones.
     */
    @PostMapping("/{id}/billing-documents/reissue")
    @AdminOnly
    public OrderResponse reissueBillingDocument(@PathVariable UUID id, Authentication auth,
                                                HttpServletRequest http) {
        User user = (User) auth.getPrincipal();
        OrderResponse order = admin.reissueBillingDocument(id, user.getId());
        record(auth, http, "admin.order.invoice_reissued", id);
        return order;
    }

    private void record(Authentication auth, HttpServletRequest http, String action, UUID orderId) {
        User user = (User) auth.getPrincipal();
        audit.record(action, user.getId(), orderId.toString(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
    }
}
