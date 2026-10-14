package ee.shop.delivery.web;

import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.common.security.AdminOnly;
import ee.shop.delivery.model.DeliveryOption;
import ee.shop.delivery.repository.DeliveryOptionRepository;
import ee.shop.delivery.web.dto.AdminDeliveryOptionRequest;
import ee.shop.delivery.web.dto.AdminDeliveryOptionView;
import ee.shop.delivery.web.dto.DeliveryOptionResponse;
import ee.shop.identity.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Delivery methods: a public read (checkout needs the menu) and admin
 * management behind @AdminOnly.
 *
 * The public list returns ACTIVE options only. A retired method still exists
 * as a row so old orders' codes stay resolvable in admin screens, but it must
 * not be offerable — and OrderService re-checks `active` at checkout anyway,
 * because a stale browser tab is exactly how a withdrawn price gets used.
 */
@RestController
public class DeliveryOptionController {

    private final DeliveryOptionRepository options;
    private final AuditService audit;

    public DeliveryOptionController(DeliveryOptionRepository options, AuditService audit) {
        this.options = options;
        this.audit = audit;
    }

    /** Public — checkout has to show these before anyone has authenticated. */
    @GetMapping("/api/delivery-options")
    public List<DeliveryOptionResponse> available() {
        return options.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(DeliveryOptionResponse::of)
                .toList();
    }

    @GetMapping("/api/admin/delivery-options")
    @AdminOnly
    public List<AdminDeliveryOptionView> all() {
        return options.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(AdminDeliveryOptionView::of)
                .toList();
    }

    @PostMapping("/api/admin/delivery-options")
    @AdminOnly
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AdminDeliveryOptionView create(@Valid @RequestBody AdminDeliveryOptionRequest req,
                                          Authentication auth, HttpServletRequest http) {
        requireOrderedLeadTimes(req);
        options.findByCode(req.code()).ifPresent(existing -> {
            throw AppException.conflict("A delivery option with code '" + req.code() + "' already exists");
        });

        DeliveryOption saved = options.save(new DeliveryOption(req.code(), req.name(), req.carrier(),
                req.price(), req.currency(), req.leadTimeDaysMin(), req.leadTimeDaysMax(),
                req.active(), req.sortOrder()));
        record(auth, http, "admin.delivery_option.created", saved.getCode());
        return AdminDeliveryOptionView.of(saved);
    }

    @PutMapping("/api/admin/delivery-options/{id}")
    @AdminOnly
    @Transactional
    public AdminDeliveryOptionView update(@PathVariable UUID id,
                                          @Valid @RequestBody AdminDeliveryOptionRequest req,
                                          Authentication auth, HttpServletRequest http) {
        requireOrderedLeadTimes(req);
        DeliveryOption option = options.findById(id)
                .orElseThrow(() -> AppException.notFound("Delivery option not found"));

        // `code` is intentionally ignored on update — orders snapshot it, and
        // changing it would orphan the reference an old order still shows.
        option.update(req.name(), req.carrier(), req.price(), req.currency(),
                req.leadTimeDaysMin(), req.leadTimeDaysMax(), req.active(), req.sortOrder());
        record(auth, http, "admin.delivery_option.updated", option.getCode());
        return AdminDeliveryOptionView.of(options.save(option));
    }

    /**
     * Retires rather than deletes. Orders snapshot their delivery details, so
     * an old order survives either way — but keeping the row means an admin
     * screen can still explain what "omniva_parcel" was.
     */
    @DeleteMapping("/api/admin/delivery-options/{id}")
    @AdminOnly
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void retire(@PathVariable UUID id, Authentication auth, HttpServletRequest http) {
        DeliveryOption option = options.findById(id)
                .orElseThrow(() -> AppException.notFound("Delivery option not found"));
        option.update(option.getName(), option.getCarrier(), option.getPrice(), option.getCurrency(),
                option.getLeadTimeDaysMin(), option.getLeadTimeDaysMax(), false, option.getSortOrder());
        options.save(option);
        record(auth, http, "admin.delivery_option.retired", option.getCode());
    }

    private static void requireOrderedLeadTimes(AdminDeliveryOptionRequest req) {
        if (req.leadTimeDaysMax() < req.leadTimeDaysMin()) {
            throw AppException.badRequest("Maximum lead time cannot be shorter than the minimum");
        }
    }

    private void record(Authentication auth, HttpServletRequest http, String action, String target) {
        User user = (User) auth.getPrincipal();
        audit.record(action, user.getId(), target, http.getRemoteAddr(), http.getHeader("User-Agent"));
    }
}
