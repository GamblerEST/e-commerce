package ee.shop.payment.banklink;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The bank-link tables from V26. */
@Repository
public class BankLinkRepository {

    public record StoredPayment(UUID orderId, String montonioOrderUuid, String paymentUrl,
                                BigDecimal amount, String currency) {}

    public record RefundAttempt(UUID id, UUID orderId, String montonioOrderUuid, String idempotencyKey,
                                BigDecimal amount, String currency, String montonioRefundUuid) {}

    private static final String PAYMENT_COLUMNS =
            "SELECT order_id, montonio_order_uuid, payment_url, amount, currency FROM bank_link_payments";
    private static final String REFUND_COLUMNS =
            "SELECT id, order_id, montonio_order_uuid, idempotency_key, amount, currency, montonio_refund_uuid "
                    + "FROM bank_link_refunds";

    private static final RowMapper<StoredPayment> PAYMENT = (rs, i) -> new StoredPayment(
            rs.getObject("order_id", UUID.class), rs.getString("montonio_order_uuid"),
            rs.getString("payment_url"), rs.getBigDecimal("amount"), rs.getString("currency"));

    private static final RowMapper<RefundAttempt> REFUND = (rs, i) -> new RefundAttempt(
            rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getString("montonio_order_uuid"), rs.getString("idempotency_key"),
            rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("montonio_refund_uuid"));

    private final JdbcTemplate jdbc;

    public BankLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredPayment> paymentForOrder(UUID orderId) {
        return jdbc.query(PAYMENT_COLUMNS + " WHERE order_id = ?", PAYMENT, orderId).stream().findFirst();
    }

    public Optional<StoredPayment> paymentByMontonioUuid(String montonioOrderUuid) {
        return jdbc.query(PAYMENT_COLUMNS + " WHERE montonio_order_uuid = ?", PAYMENT, montonioOrderUuid)
                .stream().findFirst();
    }

    /** @throws DuplicateKeyException when the order already has one: a concurrent retry got there first */
    public void savePayment(UUID orderId, String montonioOrderUuid, String paymentUrl, String bankCode,
                            BigDecimal amount, String currency) {
        jdbc.update("INSERT INTO bank_link_payments (order_id, montonio_order_uuid, payment_url, bank_code, amount, currency) "
                + "VALUES (?, ?, ?, ?, ?, ?)", orderId, montonioOrderUuid, paymentUrl, bankCode, amount, currency);
    }

    /** @return true only for the first sighting of this result; every copy after it is already handled */
    public boolean claimResult(String montonioOrderUuid, String paymentStatus) {
        return jdbc.update("INSERT INTO processed_bank_link_results (montonio_order_uuid, payment_status) "
                + "VALUES (?, ?) ON CONFLICT DO NOTHING", montonioOrderUuid, paymentStatus) == 1;
    }

    /** Undoes a claim whose result could not be handed on, so Montonio's retry is not swallowed. */
    public void releaseResult(String montonioOrderUuid, String paymentStatus) {
        jdbc.update("DELETE FROM processed_bank_link_results WHERE montonio_order_uuid = ? AND payment_status = ?",
                montonioOrderUuid, paymentStatus);
    }

    public Optional<RefundAttempt> unresolvedRefund(UUID orderId) {
        return jdbc.query(REFUND_COLUMNS + " WHERE order_id = ? AND outcome IS NULL ORDER BY created_at DESC LIMIT 1",
                REFUND, orderId).stream().findFirst();
    }

    public List<RefundAttempt> unresolvedRefunds() {
        return jdbc.query(REFUND_COLUMNS + " WHERE outcome IS NULL ORDER BY created_at", REFUND);
    }

    public int refundAttempts(UUID orderId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM bank_link_refunds WHERE order_id = ?",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    /** @throws DuplicateKeyException when a concurrent request created the same attempt */
    public void insertRefund(UUID orderId, String montonioOrderUuid, String idempotencyKey,
                             BigDecimal amount, String currency) {
        jdbc.update("INSERT INTO bank_link_refunds (order_id, montonio_order_uuid, idempotency_key, amount, currency) "
                + "VALUES (?, ?, ?, ?, ?)", orderId, montonioOrderUuid, idempotencyKey, amount, currency);
    }

    public void recordRefundUuid(UUID refundId, String montonioRefundUuid) {
        jdbc.update("UPDATE bank_link_refunds SET montonio_refund_uuid = ? WHERE id = ? AND montonio_refund_uuid IS NULL",
                montonioRefundUuid, refundId);
    }

    /**
     * Settles an attempt. A single guarded UPDATE, so of two concurrent callers
     * only one gets true, and only that one appends the order event.
     */
    public boolean resolveRefund(UUID refundId, String outcome) {
        return jdbc.update("UPDATE bank_link_refunds SET outcome = ?, resolved_at = now() WHERE id = ? AND outcome IS NULL",
                outcome, refundId) == 1;
    }
}
