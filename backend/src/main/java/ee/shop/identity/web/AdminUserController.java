package ee.shop.identity.web;

import ee.shop.common.audit.AuditService;
import ee.shop.common.security.AdminOnly;
import ee.shop.common.security.StaffOnly;
import ee.shop.identity.model.User;
import ee.shop.identity.service.AdminUserService;
import ee.shop.identity.web.dto.AdminUserView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Account administration.
 *
 * Note the split: reading accounts is @StaffOnly (support genuinely needs to
 * look someone up to help them), but changing a role or disabling an account
 * is @AdminOnly. A support agent who can grant roles is not a support agent —
 * it is an administrator with extra steps, and the whole point of the second
 * role is that the everyday job doesn't carry that power.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService admin;
    private final AuditService audit;

    public AdminUserController(AdminUserService admin, AuditService audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping
    @StaffOnly
    public List<AdminUserView> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        return admin.list(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/{id}")
    @StaffOnly
    public AdminUserView get(@PathVariable UUID id) {
        return admin.get(id);
    }

    @PutMapping("/{id}/role")
    @AdminOnly
    public AdminUserView changeRole(@PathVariable UUID id,
                                     @RequestParam User.Role role,
                                     Authentication auth, HttpServletRequest http) {
        UUID actorId = ((User) auth.getPrincipal()).getId();
        AdminUserView updated = admin.changeRole(actorId, id, role);
        // Role changes are the highest-value thing in this log: "who made
        // this account an admin, and when" is unanswerable afterwards
        // otherwise (audit_log is append-only).
        audit.record("admin.user.role_changed", actorId, id + " -> " + role,
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        return updated;
    }

    @PutMapping("/{id}/disabled")
    @AdminOnly
    public AdminUserView setDisabled(@PathVariable UUID id,
                                      @RequestParam boolean disabled,
                                      Authentication auth, HttpServletRequest http) {
        UUID actorId = ((User) auth.getPrincipal()).getId();
        AdminUserView updated = admin.setDisabled(actorId, id, disabled);
        audit.record(disabled ? "admin.user.disabled" : "admin.user.enabled", actorId, id.toString(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        return updated;
    }
}
