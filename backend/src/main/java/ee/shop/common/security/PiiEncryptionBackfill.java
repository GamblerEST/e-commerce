package ee.shop.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encrypts personal data written BEFORE encryption-at-rest existed.
 *
 * V20 made the schema able to hold ciphertext, but it could not encrypt the
 * rows already there: the key lives in the application, never in the database
 * (that separation is the entire point — a dump without the key is useless).
 * So the backfill has to run here, once, on the application side.
 *
 * Without it, "all personal data is encrypted at rest" would be true only of
 * rows written after the deploy, and the seeded demo accounts would sit in
 * clear text indefinitely — a gap that is easy to overlook precisely because
 * everything new looks correct.
 *
 * Safe to run on every boot. FieldCipher.encrypt() returns an
 * already-prefixed value unchanged, and each statement filters on
 * NOT LIKE 'enc:v1:%', so a second run matches nothing and does nothing.
 *
 * Deliberately NOT a Flyway migration: Flyway runs SQL against the database,
 * which by design has no access to the key.
 */
@Component
public class PiiEncryptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PiiEncryptionBackfill.class);

    /**
     * Bounded so a very large table cannot stall startup indefinitely. If a
     * run hits the cap it says so, and the next boot continues — the filter
     * makes progress monotonic.
     */
    private static final int MAX_ROWS_PER_RUN = 50_000;

    private static final List<String> ORDER_PII_COLUMNS = List.of(
            "billing_name", "billing_email", "billing_phone", "billing_address_line1",
            "billing_address_line2", "billing_city", "billing_postal_code", "billing_country");

    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;
    private final EmailHasher emailHasher;

    public PiiEncryptionBackfill(JdbcTemplate jdbc, FieldCipher cipher, EmailHasher emailHasher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.emailHasher = emailHasher;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Emails FIRST. The blind index is what every lookup uses, so a row
        // whose email is encrypted but unhashed is an account nobody can log
        // into — doing them in one statement each keeps the two in step.
        int emails = backfillEmails();
        int users = backfillUsers();
        int orders = backfillOrders();

        if (emails > 0) {
            log.info("Encrypted {} email address(es) and built their blind index", emails);
        }
        if (users > 0 || orders > 0) {
            // Worth a log line at INFO: it is the only evidence that rows were
            // rewritten, and an operator seeing it once should NOT see it again.
            log.info("Encrypted personal data at rest for {} account(s) and {} order(s)",
                    users, orders);
        }
    }

    /**
     * Encrypts users.email and computes its blind index (V24).
     *
     * Both columns are written in ONE statement per row. Encrypting the address
     * without storing the hash would make the account unfindable — login looks
     * up by hash — so a half-applied backfill must not be possible.
     */
    private int backfillEmails() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, email FROM users "
                        + "WHERE email IS NOT NULL AND email_hash IS NULL "
                        + "LIMIT " + MAX_ROWS_PER_RUN);

        for (Map<String, Object> row : rows) {
            // decrypt() first, ALWAYS. It returns an unprefixed value
            // unchanged, so this reads as the plaintext address whether the row
            // predates encryption or is a row that somehow got encrypted
            // without a hash. Hashing the stored value directly would, in that
            // second case, silently index the ciphertext — a hash that matches
            // nothing anyone can ever type, and an account locked out with no
            // error anywhere.
            String email = cipher.decrypt((String) row.get("email"));
            jdbc.update("UPDATE users SET email = ?, email_hash = ? WHERE id = ?",
                    cipher.encrypt(email), emailHasher.hash(email), (UUID) row.get("id"));
        }
        return rows.size();
    }

    private int backfillUsers() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, display_name FROM users "
                        + "WHERE display_name IS NOT NULL AND display_name NOT LIKE ? "
                        + "LIMIT " + MAX_ROWS_PER_RUN,
                FieldCipher.PREFIX + "%");

        for (Map<String, Object> row : rows) {
            jdbc.update("UPDATE users SET display_name = ? WHERE id = ?",
                    cipher.encrypt((String) row.get("display_name")), (UUID) row.get("id"));
        }
        return rows.size();
    }

    private int backfillOrders() {
        // Keyed off billing_name: every order has one, and all of an order's
        // personal columns were written together, so one is a reliable marker
        // for whether the row predates encryption.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, " + String.join(", ", ORDER_PII_COLUMNS) + " FROM orders "
                        + "WHERE billing_name NOT LIKE ? "
                        + "LIMIT " + MAX_ROWS_PER_RUN,
                FieldCipher.PREFIX + "%");

        for (Map<String, Object> row : rows) {
            Object[] values = new Object[ORDER_PII_COLUMNS.size() + 1];
            for (int i = 0; i < ORDER_PII_COLUMNS.size(); i++) {
                values[i] = cipher.encrypt((String) row.get(ORDER_PII_COLUMNS.get(i)));
            }
            values[ORDER_PII_COLUMNS.size()] = row.get("id");

            String assignments = String.join(", ",
                    ORDER_PII_COLUMNS.stream().map(c -> c + " = ?").toList());
            jdbc.update("UPDATE orders SET " + assignments + " WHERE id = ?", values);
        }
        return rows.size();
    }
}
