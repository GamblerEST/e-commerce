package ee.shop.identity.service;

import ee.shop.common.error.AppException;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.web.dto.AdminUserView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Account administration. Authorization is on the
 * controller; the guards here are about not letting an administrator
 * accidentally destroy their own ability to administer.
 *
 * The lockout risk is real and specific: this shop has no out-of-band admin
 * recovery — no console, no "promote via SQL" runbook an operator could
 * follow, and AuthController deliberately won't hard-block a 2FA-less admin
 * at login precisely because stranding the last administrator is worse than
 * the gap it would close. So the last admin standing must not be able to
 * demote or disable themselves in one click.
 */
@Service
public class AdminUserService {

    private final UserRepository users;

    public AdminUserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AdminUserView> list(int page, int size) {
        Page<User> result = users.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return result.getContent().stream().map(AdminUserView::of).toList();
    }

    @Transactional(readOnly = true)
    public AdminUserView get(UUID id) {
        return AdminUserView.of(find(id));
    }

    /**
     * Changing a role is the single most dangerous administrative action
     * here — it is how an attacker with one compromised admin session makes
     * their own persistent backdoor, and how a careless click removes the
     * last administrator. Hence: self-changes refused outright, and every
     * change audited by the caller.
     */
    @Transactional
    public AdminUserView changeRole(UUID actorId, UUID targetId, User.Role role) {
        if (actorId.equals(targetId)) {
            // Refusing SELF-change (rather than only self-demotion) keeps the
            // rule simple enough to reason about, and there is no legitimate
            // reason to need it: another admin can do it, and if you are the
            // only admin, the demotion is exactly what must not happen.
            throw AppException.conflict("You cannot change your own role — ask another administrator");
        }
        User target = find(targetId);
        if (target.getAnonymizedAt() != null) {
            throw AppException.conflict("This account has been anonymized and cannot be modified");
        }
        target.changeRole(role);
        return AdminUserView.of(users.save(target));
    }

    @Transactional
    public AdminUserView setDisabled(UUID actorId, UUID targetId, boolean disabled) {
        if (actorId.equals(targetId) && disabled) {
            throw AppException.conflict("You cannot disable your own account");
        }
        User target = find(targetId);
        if (target.getAnonymizedAt() != null) {
            throw AppException.conflict("This account has been anonymized and cannot be modified");
        }
        if (disabled) {
            target.disable();
        } else {
            target.enable();
        }
        return AdminUserView.of(users.save(target));
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() -> AppException.notFound("User not found"));
    }
}
