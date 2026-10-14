package ee.shop.common.security;

import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves personal data is unreadable in the database itself.
 *
 * These assertions read the raw columns with JDBC, deliberately bypassing JPA
 * — going through the entity would decrypt on the way out and prove nothing.
 * The question being answered is the one that actually matters: if someone
 * walks off with a database dump, can they read the customer's address?
 */
@Tag("api")
@Tag("security")
class EncryptionAtRestTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EmailHasher emailHasher;
    @Autowired FieldCipher cipher;

    // ── the cipher itself ────────────────────────────────────────────────

    @Test
    void roundTrips() {
        String plaintext = "Pikk tn 1, Tallinn";
        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).startsWith(FieldCipher.PREFIX);
        assertThat(encrypted).doesNotContain(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void sameInputEncryptsDifferentlyEachTime() {
        // A random IV per encryption. This is what stops an attacker learning
        // that two orders share an address without decrypting either — and it
        // is also exactly why these columns can never be indexed or searched.
        String a = cipher.encrypt("Männiku talu");
        String b = cipher.encrypt("Männiku talu");

        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo(cipher.decrypt(b)).isEqualTo("Männiku talu");
    }

    @Test
    void handlesEstonianCharacters() {
        // Round-tripping through UTF-8 has to survive the characters real
        // Estonian names and addresses actually use.
        String plaintext = "Jõgeva õunaaed, Kärdla, Väike-Õismäe, Šokolaadi tänav";
        assertThat(cipher.decrypt(cipher.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void tamperedCiphertextIsRejectedRatherThanSilentlyWrong() {
        // GCM is authenticated: a flipped byte must fail loudly. Returning
        // some other plaintext would put a wrong address on an invoice.
        String encrypted = cipher.encrypt("Pikk tn 1");
        char[] chars = encrypted.toCharArray();
        int last = chars.length - 1;
        chars[last] = chars[last] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> cipher.decrypt(new String(chars)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullAndEmptyPassThrough() {
        // An absent value is not a secret, and encrypting "" would turn a
        // nullable optional field into a non-empty string.
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void alreadyEncryptedValueIsNotDoubleEncrypted() {
        String once = cipher.encrypt("Tartu mnt 5");
        assertThat(cipher.encrypt(once)).isEqualTo(once);
    }

    @Test
    void legacyPlaintextIsReadableSoOldRowsKeepWorking() {
        // Rows written before V20 have no prefix. They must still render, or
        // deploying this feature would break every historical order.
        assertThat(cipher.decrypt("Pikk tn 1, Tallinn")).isEqualTo("Pikk tn 1, Tallinn");
    }

    // ── the columns as stored ────────────────────────────────────────────

    @Test
    void orderBillingDetailsAreCiphertextInTheDatabase() {
        UUID orderId = UUID.randomUUID();
        String name = "Kadri Tamm";
        String address = "Pikk tn 1";
        String city = "Tallinn";
        String phone = "+372 5555 1234";

        // Written through the entity manager path is what production does, but
        // this test needs a row it fully controls, so it inserts the values
        // ALREADY encrypted (as the converter would) and then checks the
        // stored bytes are not the plaintext.
        jdbc.update("""
                INSERT INTO orders (id, idempotency_key, billing_name, billing_email,
                                    billing_phone, billing_address_line1, billing_city,
                                    billing_postal_code, billing_country, currency,
                                    subtotal, vat_total, total)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'EUR', 10.00, 2.20, 12.20)
                """,
                orderId, "enc-test-" + orderId,
                cipher.encrypt(name), cipher.encrypt("kadri@example.ee"),
                cipher.encrypt(phone), cipher.encrypt(address), cipher.encrypt(city),
                cipher.encrypt("10123"), cipher.encrypt("EE"));

        var row = jdbc.queryForMap(
                "SELECT billing_name, billing_address_line1, billing_city, billing_phone "
                        + "FROM orders WHERE id = ?", orderId);

        // The dump-reader test: none of the personal values appear in the
        // stored columns, and every one carries the version prefix.
        for (Object stored : row.values()) {
            assertThat((String) stored).startsWith(FieldCipher.PREFIX);
        }
        assertThat(row.values().toString())
                .doesNotContain(name)
                .doesNotContain(address)
                .doesNotContain(city)
                .doesNotContain(phone);

        // And it still reads back correctly through the cipher.
        assertThat(cipher.decrypt((String) row.get("billing_name"))).isEqualTo(name);
        assertThat(cipher.decrypt((String) row.get("billing_address_line1"))).isEqualTo(address);
    }

    /**
     * The backfill is what makes "all personal data is encrypted" true of rows
     * that already existed, not just new ones. Seeded demo accounts are
     * written by a Flyway migration in clear text — SQL has no access to the
     * key — so without this step they would stay readable forever while
     * everything new looked correct.
     */
    @Test
    void backfillLeavesNoPlaintextPersonalDataBehind() {
        // The runner executes at application startup, which has already
        // happened by the time this test runs.
        Integer plaintextUsers = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE display_name IS NOT NULL "
                        + "AND display_name NOT LIKE 'enc:v1:%'", Integer.class);
        Integer plaintextOrders = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE billing_name NOT LIKE 'enc:v1:%'",
                Integer.class);

        // Named in the failure message, so a regression says WHICH row rather
        // than just "expected 0 but was 1".
        assertThat(plaintextUsers)
                .as("accounts still holding a plaintext display_name: %s",
                        jdbc.queryForList("SELECT id, display_name FROM users "
                                + "WHERE display_name IS NOT NULL "
                                + "AND display_name NOT LIKE 'enc:v1:%' LIMIT 5"))
                .isZero();
        assertThat(plaintextOrders)
                .as("orders still holding a plaintext billing_name: %s",
                        jdbc.queryForList("SELECT id FROM orders "
                                + "WHERE billing_name NOT LIKE 'enc:v1:%' LIMIT 5"))
                .isZero();
    }

    /**
     * A seeded reviewer's name must still be readable after being encrypted.
     *
     * The rows are addressed by the blind index of the exact addresses V19
     * seeds, not by a LIKE on the email column: since V24 that column holds
     * ciphertext, so a pattern match on it can only ever return nothing.
     * Widening the predicate to "any row with a display_name" was the tempting
     * shortcut and would have quietly turned this into a test of JPA-written
     * rows -- the opposite of the backfill it exists to cover.
     */
    @Test
    void backfilledNamesStillDecrypt() {
        List<String> seededReviewerHashes = IntStream.rangeClosed(1, 20)
                .mapToObj(n -> emailHasher.hash("reviewer" + n + "@example.ee"))
                .toList();

        List<String> stored = jdbc.queryForList(
                "SELECT display_name FROM users WHERE email_hash = ANY (?) "
                        + "AND display_name IS NOT NULL",
                String.class,
                new Object[] { seededReviewerHashes.toArray(String[]::new) });

        assertThat(stored)
                .as("V19 seeds 20 demo reviewers; the backfill must have kept them addressable")
                .hasSize(20);
        assertThat(stored).allSatisfy(value -> {
            assertThat(value).startsWith(FieldCipher.PREFIX);
            assertThat(cipher.decrypt(value)).isNotBlank().doesNotStartWith(FieldCipher.PREFIX);
        });
    }

    /**
     * The counterpart to every test above: money is deliberately NOT encrypted,
     * and that has to be a decision the schema keeps rather than a gap anyone
     * can close by accident.
     *
     * Encrypting an amount means storing base64 in a TEXT column, which throws
     * away the two things the database guarantees about money for EVERY writer
     * -- the scale, and the sign. This asserts both survive, so a future change
     * that converts these columns has to delete a test that says why, instead
     * of quietly trading a database guarantee for an application promise.
     */
    @Test
    void orderAmountsStayNumericAndConstrained() {
        record MoneyColumn(String table, String column, int precision, int scale) { }
        List<MoneyColumn> money = List.of(
                new MoneyColumn("orders", "subtotal", 12, 2),
                new MoneyColumn("orders", "vat_total", 12, 2),
                new MoneyColumn("orders", "total", 12, 2),
                new MoneyColumn("order_lines", "unit_price", 12, 2),
                new MoneyColumn("order_lines", "line_total", 12, 2),
                new MoneyColumn("order_lines", "vat_rate", 5, 4));

        for (MoneyColumn m : money) {
            Map<String, Object> type = jdbc.queryForMap(
                    "SELECT data_type, numeric_precision, numeric_scale "
                            + "FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = ?",
                    m.table(), m.column());

            assertThat(type.get("data_type"))
                    .as("%s.%s must stay NUMERIC - a float loses cents, ciphertext loses the type",
                            m.table(), m.column())
                    .isEqualTo("numeric");
            assertThat(type.get("numeric_precision")).isEqualTo(m.precision());
            assertThat(type.get("numeric_scale")).isEqualTo(m.scale());
        }

        // And the database still refuses a negative amount, rather than
        // trusting the application to have checked. Asserted against a row this
        // test creates: the UPDATE is expected to fail, but an UPDATE aimed at
        // a seeded order would be a fixture writing to data other tests read.
        UUID orderId = insertOrderWithEncryptedPii(cipher.encrypt("EE"));
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET total = -1 WHERE id = ?", orderId))
                .as("the CHECK (total >= 0) constraint must still be enforced by the database")
                .hasMessageContaining("total");
    }

    @Test
    void billingCountryColumnIsWideEnoughForCiphertext() {
        // billing_country was VARCHAR(2). V20 widens it — without that, every
        // checkout would fail on a value-too-long error the moment encryption
        // was switched on.
        String ciphertext = cipher.encrypt("EE");
        assertThat(ciphertext.length()).isGreaterThan(2);

        UUID orderId = insertOrderWithEncryptedPii(ciphertext);

        assertThat(jdbc.queryForObject(
                "SELECT billing_country FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo(ciphertext);
    }

    /**
     * An order row of this test class's own making.
     *
     * Every personal column is written ENCRYPTED, not just the one under test.
     * Filling the others with plaintext 'x' would leave a row that
     * backfillLeavesNoPlaintextPersonalDataBehind then correctly flags -- a
     * fixture manufacturing the very state another test forbids.
     */
    private UUID insertOrderWithEncryptedPii(String billingCountry) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, idempotency_key, billing_name, billing_email,
                                    billing_address_line1, billing_city, billing_postal_code,
                                    billing_country, currency, subtotal, vat_total, total)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'EUR', 0, 0, 0)
                """, orderId, "enc-at-rest-" + orderId,
                cipher.encrypt("x"), cipher.encrypt("x"), cipher.encrypt("x"),
                cipher.encrypt("x"), cipher.encrypt("x"), billingCountry);
        return orderId;
    }
}
