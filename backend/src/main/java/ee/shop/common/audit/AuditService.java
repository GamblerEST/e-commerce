package ee.shop.common.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes to the append-only audit_log.
 *
 * Plain JDBC rather than JPA: audit rows are write-once and never loaded as
 * entities, so an @Entity would buy us nothing but a persistence-context entry
 * and the ability to accidentally call save() on a modified copy.
 *
 * REQUIRES_NEW is deliberate. Consider a failed login: we throw
 * AppException.unauthorized, which rolls back the surrounding transaction. If
 * the audit write shared that transaction it would roll back TOO — and we would
 * have no record of failed logins, which is precisely the event we most want
 * recorded. The audit entry must survive the failure of the thing it describes.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, UUID actorId, String target, String ip, String userAgent) {
        jdbc.update("""
            INSERT INTO audit_log (actor_id, action, target_id, ip, user_agent)
            VALUES (?, ?, ?, ?::inet, ?)
            """, actorId, action, target, ip, userAgent);
    }
}
