package ee.shop.common.audit;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DECISIONS.md §8 claims the shop_runtime role is INCAPABLE (not merely
 * forbidden) of altering audit_log, and lists exactly which attacks were
 * used to verify that: UPDATE, DELETE, TRUNCATE, DROP TRIGGER, ALTER OWNER,
 * GRANT-to-self, DROP TABLE. That verification was a one-time manual check
 * once during setup — nothing re-ran it since. This automates it, so a
 * future migration that accidentally widens shop_app's grants (V2__least_
 * privilege.sql) gets caught by CI instead of by an actual breach.
 *
 * Connects as shop_runtime over a REAL second connection (not the app's own
 * datasource, which runs migrations as the owner) — the same network-level
 * authentication a real attacker with shop_runtime's credentials would have,
 * not a Java-level permission check that could be bypassed by different code.
 *
 * The password set below is disposable: it only ever exists on this
 * Testcontainers instance, which is destroyed at the end of the test run.
 */
@Tag("api")
@Tag("security")
class AuditLogTamperProofTest extends IntegrationTest {

    private static final String PERMISSION_DENIED = "permission denied";
    private static final String MUST_BE_OWNER = "must be owner";

    @Autowired JdbcTemplate jdbc;

    /**
     * No setup step any more, and that is the point.
     *
     * This test used to set shop_runtime's password itself with ALTER ROLE,
     * which worked only because the test's own connection was the database
     * superuser. Now that the application (and therefore this test's
     * JdbcTemplate) connects AS shop_runtime, that statement is correctly
     * refused — the runtime role cannot alter roles, or it could grant itself
     * back the very privileges this test exists to prove it lacks.
     *
     * The password is set by R__runtime_role.sql, run by Flyway under the
     * owner's credentials, exactly as it is in production.
     */
    private Connection connectAsShopRuntime() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "shop_runtime", RUNTIME_PASSWORD);
    }

    @Test
    void shopRuntime_canInsertAndSelect_butCannotUpdateDeleteTruncateOrAlterAuditLog() throws SQLException {
        try (Connection conn = connectAsShopRuntime()) {
            // INSERT must succeed — this is the role's whole job.
            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO audit_log (action, ip) VALUES ('test.tamper_probe', '127.0.0.1'::inet)");
            }

            // SELECT must succeed.
            try (Statement s = conn.createStatement()) {
                var rs = s.executeQuery("SELECT count(*) FROM audit_log WHERE action = 'test.tamper_probe'");
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            }

            // Verified against the real error text (not guessed) — Postgres
            // gives a different message depending on WHY the command is
            // rejected: missing privilege vs. not being the table owner.
            assertFails(conn, "UPDATE audit_log SET action = 'tampered' WHERE action = 'test.tamper_probe'",
                    PERMISSION_DENIED);
            assertFails(conn, "DELETE FROM audit_log WHERE action = 'test.tamper_probe'",
                    PERMISSION_DENIED);

            // TRUNCATE is not covered by DELETE privilege and doesn't fire
            // row triggers — this is the specific gap DECISIONS.md §8 calls
            // out as "a genuinely common oversight" if only relying on a
            // BEFORE DELETE trigger.
            assertFails(conn, "TRUNCATE audit_log", PERMISSION_DENIED);

            // Not the table owner, so these fail on ownership, not grants.
            assertFails(conn, "DROP TRIGGER audit_log_no_update ON audit_log", MUST_BE_OWNER);
            assertFails(conn, "ALTER TABLE audit_log OWNER TO shop_runtime", MUST_BE_OWNER);
            assertFails(conn, "DROP TABLE audit_log", MUST_BE_OWNER);

            // GRANT-to-self is the odd one out: Postgres doesn't ERROR here,
            // it silently no-ops (shop_runtime has no grantable privileges to
            // pass on, so there's nothing to warn about failing). The real
            // security property isn't "does this throw" — it's "did it
            // actually grant anything" — so prove UPDATE still fails after.
            try (Statement s = conn.createStatement()) {
                s.execute("GRANT UPDATE ON audit_log TO shop_runtime");
            }
            assertFails(conn, "UPDATE audit_log SET action = 'tampered' WHERE action = 'test.tamper_probe'",
                    PERMISSION_DENIED);
        }
    }

    private void assertFails(Connection conn, String sql, String expectedMessageFragment) {
        assertThatThrownBy(() -> {
            try (Statement s = conn.createStatement()) {
                s.execute(sql);
            }
        }).isInstanceOf(SQLException.class)
          .hasMessageContaining(expectedMessageFragment);
    }
}
