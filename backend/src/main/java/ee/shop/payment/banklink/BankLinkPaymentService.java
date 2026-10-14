package ee.shop.payment.banklink;

import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.common.error.FieldValidationException;
import ee.shop.config.InventoryProperties;
import ee.shop.config.MontonioProperties;
import ee.shop.payment.banklink.BankLinkRepository.RefundAttempt;
import ee.shop.payment.banklink.BankLinkRepository.StoredPayment;
import ee.shop.payment.banklink.MontonioClient.BankOption;
import ee.shop.payment.messaging.PaymentResultMessage;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static ee.shop.config.RabbitConfig.PAYMENT_EXCHANGE;
import static ee.shop.config.RabbitConfig.PAYMENT_RESULT_ROUTING_KEY;
import static ee.shop.payment.banklink.MontonioJwt.text;

/**
 * Estonian bank-link payments through Montonio.
 *
 * The customer is sent to their own bank to pay; the result comes back as a token
 * signed with the store's secret key, by webhook and again when the customer
 * returns. Either copy settles the order the same way card payments do: a
 * message on the payment-result queue, which appends the order event.
 *
 * Nothing about a result is trusted until the signature verifies, the token
 * carries this store's access key, and it describes exactly the payment that was
 * started: the same order, the same amount, the same currency.
 */
@Service
public class BankLinkPaymentService {

    /** Results that settle an order. PENDING and AUTHORIZED are not results yet. */
    private static final Set<String> SETTLING = Set.of("PAID", "ABANDONED", "VOIDED");

    private static final Duration BANK_LIST_TTL = Duration.ofMinutes(10);

    public record AcceptedResult(UUID orderId, String status) {}

    public record SettledRefund(UUID orderId, String outcome) {
        public boolean successful() {
            return "SUCCESSFUL".equals(outcome);
        }
    }

    private record CachedBanks(List<BankOption> banks, Instant fetchedAt) {}

    private final MontonioProperties props;
    private final MontonioClient montonio;
    private final BankLinkRepository store;
    private final RabbitTemplate rabbit;
    private final AuditService audit;
    private final Duration paymentWindow;
    private final String siteUrl;
    private volatile CachedBanks cachedBanks;

    public BankLinkPaymentService(MontonioProperties props, MontonioClient montonio, BankLinkRepository store,
                                  RabbitTemplate rabbit, AuditService audit, InventoryProperties inventory,
                                  @Value("${app.frontend-url}") String siteUrl) {
        this.props = props;
        this.montonio = montonio;
        this.store = store;
        this.rabbit = rabbit;
        this.audit = audit;
        this.paymentWindow = inventory.reservationTtl();
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    /** Montonio's Estonian banks, fetched at most every ten minutes. */
    public List<BankOption> banks() {
        if (!props.enabled()) {
            return List.of();
        }
        CachedBanks cached = cachedBanks;
        if (cached != null && cached.fetchedAt().plus(BANK_LIST_TTL).isAfter(Instant.now())) {
            return cached.banks();
        }
        List<BankOption> fresh = montonio.estonianBanks();
        cachedBanks = new CachedBanks(fresh, Instant.now());
        return fresh;
    }

    /**
     * Checked before the order exists. An order created with a bank nobody can pay
     * through would hold its stock until the reservation expired.
     */
    public void requireSelectableBank(String bankCode) {
        if (!props.enabled()) {
            throw new FieldValidationException("paymentMethod", "Bank payments are not available.");
        }
        if (bankCode == null || banks().stream().noneMatch(b -> b.code().equals(bankCode))) {
            throw new FieldValidationException("bankCode", "Choose your bank.");
        }
    }

    public boolean paidByBankLink(UUID orderId) {
        return store.paymentForOrder(orderId).isPresent();
    }

    /**
     * The page at the customer's bank for an order that has already been committed.
     * A network call, so never made inside the order's transaction. A retried
     * checkout gets the same page back instead of a second payment.
     */
    public String startPayment(UUID orderId, String reference, BigDecimal total, String currency, String bankCode) {
        if (!props.enabled()) {
            throw AppException.unavailable("Bank payments are not available.");
        }
        StoredPayment existing = store.paymentForOrder(orderId).orElse(null);
        if (existing != null) {
            return existing.paymentUrl();
        }

        Map<String, Object> methodOptions = new LinkedHashMap<>();
        methodOptions.put("preferredProvider", bankCode);
        methodOptions.put("preferredCountry", "EE");
        methodOptions.put("paymentDescription", reference);
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("method", "paymentInitiation");
        payment.put("amount", total);
        payment.put("currency", currency);
        payment.put("methodOptions", methodOptions);

        // No customer name, email or address is sent: the bank collects what it
        // needs itself, and nothing more than the order and amount leaves this shop.
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("merchantReference", orderId.toString());
        order.put("returnUrl", siteUrl + "/checkout/bank-link/return");
        order.put("notificationUrl", siteUrl + "/api/payments/bank-link/notification");
        order.put("grandTotal", total);
        order.put("currency", currency);
        order.put("locale", "en");
        // In minutes. The link lasts as long as the stock is held for it, so a
        // payment cannot be completed for stock the shop has already released.
        order.put("expiresIn", Math.max(1, paymentWindow.toMinutes()));
        order.put("payment", payment);

        MontonioClient.CreatedOrder created = montonio.createOrder(order);
        if (!created.paymentUrl().startsWith("https://")) {
            throw new AppException(MontonioClient.UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        }
        try {
            store.savePayment(orderId, created.uuid(), created.paymentUrl(), bankCode, total, currency);
            return created.paymentUrl();
        } catch (DuplicateKeyException raced) {
            return store.paymentForOrder(orderId).orElseThrow().paymentUrl();
        }
    }

    public String existingPaymentUrl(UUID orderId) {
        return store.paymentForOrder(orderId).map(StoredPayment::paymentUrl).orElse(null);
    }

    /**
     * A signed result, from the webhook or from the customer's return; both carry
     * the same token and are handled identically.
     *
     * @return the order it belongs to, and what it means for the customer:
     *         PAID, FAILED, or PENDING when it does not settle anything yet
     */
    public AcceptedResult acceptResult(String orderToken) {
        if (!props.enabled()) {
            throw AppException.unavailable("Bank payments are not available.");
        }
        JsonNode claims = verified(orderToken);
        String montonioOrderUuid = text(claims.path("uuid"));
        StoredPayment payment = montonioOrderUuid == null ? null
                : store.paymentByMontonioUuid(montonioOrderUuid).orElse(null);
        if (payment == null) {
            throw AppException.badRequest("This payment result is not for an order in this shop");
        }

        // A genuine token for some other payment must not settle this order. The
        // signature proves Montonio wrote it; these prove what it is about.
        if (!payment.orderId().toString().equals(text(claims.path("merchantReference")))
                || !sameAmount(payment.amount(), claims.path("grandTotal"))
                || !payment.currency().equals(text(claims.path("currency")))) {
            audit.record("payment.bank_link_result_mismatch", null, "order " + payment.orderId(), null, null);
            throw AppException.badRequest("This payment result does not match its order");
        }

        String status = text(claims.path("paymentStatus"));
        if (status == null || !SETTLING.contains(status)) {
            return new AcceptedResult(payment.orderId(), "PENDING");
        }
        String outcome = "PAID".equals(status) ? "PAID" : "FAILED";
        if (!store.claimResult(montonioOrderUuid, status)) {
            return new AcceptedResult(payment.orderId(), outcome);
        }

        if ("VOIDED".equals(status)) {
            // A bank can reverse a payment it already confirmed. The queue only settles
            // an order still waiting for payment, so on an order already marked paid
            // this changes nothing by itself; the audit log is how staff find out.
            audit.record("payment.bank_link_voided", null, "order " + payment.orderId(), null, null);
        }
        try {
            rabbit.convertAndSend(PAYMENT_EXCHANGE, PAYMENT_RESULT_ROUTING_KEY,
                    new PaymentResultMessage(payment.orderId(), "PAID".equals(status), failureReason(status)));
        } catch (AmqpException e) {
            // The claim would otherwise make Montonio's retry look like a duplicate and
            // the result would be lost for good.
            store.releaseResult(montonioOrderUuid, status);
            throw e;
        }
        return new AcceptedResult(payment.orderId(), outcome);
    }

    /** What the customer is told in the payment-failed email. */
    private static String failureReason(String status) {
        return switch (status) {
            case "PAID" -> null;
            case "VOIDED" -> "The bank reversed the payment.";
            default -> "The bank payment was not completed.";
        };
    }

    /** A refund notification only means "look again"; its contents are not relied on. */
    public void verifyRefundNotification(String refundToken) {
        if (!props.enabled()) {
            throw AppException.unavailable("Bank payments are not available.");
        }
        verified(refundToken);
    }

    /**
     * Asks Montonio to refund a bank-link order in full. The outcome arrives later;
     * see settleFinishedRefunds.
     */
    public void requestRefund(UUID orderId) {
        if (!props.enabled()) {
            throw AppException.unavailable("Bank payments are not available, so this order cannot be refunded here.");
        }
        StoredPayment payment = store.paymentForOrder(orderId)
                .orElseThrow(() -> AppException.conflict("No bank payment found for this order"));
        RefundAttempt attempt = currentAttempt(orderId, payment);
        try {
            MontonioClient.RefundRequest sent = montonio.createRefund(
                    payment.montonioOrderUuid(), attempt.amount(), attempt.idempotencyKey());
            if (sent.refundUuid() != null) {
                store.recordRefundUuid(attempt.id(), sent.refundUuid());
            }
        } catch (MontonioClient.RefusedException refused) {
            // Montonio said no and created nothing, so this attempt is over and the next
            // may use a new key. A timeout is different and is not caught here: the
            // refund may exist, so the attempt stays open and a retry reuses its key.
            store.resolveRefund(attempt.id(), "REJECTED");
            throw refused;
        }
        audit.record("payment.bank_link_refund_requested", null, "order " + orderId, null, null);
    }

    /** Refund attempts Montonio has finished with, each settled exactly once. */
    public List<SettledRefund> settleFinishedRefunds() {
        if (!props.enabled()) {
            return List.of();
        }
        List<SettledRefund> settled = new ArrayList<>();
        for (RefundAttempt attempt : store.unresolvedRefunds()) {
            JsonNode order;
            try {
                order = montonio.order(attempt.montonioOrderUuid());
            } catch (AppException unreachable) {
                break; // the next run tries again
            }
            String outcome = outcomeOf(order, attempt);
            if (outcome != null && store.resolveRefund(attempt.id(), outcome)) {
                settled.add(new SettledRefund(attempt.orderId(), outcome));
            }
        }
        return settled;
    }

    /**
     * Montonio's documented order view: a full refund leaves paymentStatus REFUNDED;
     * a failed one shows up as that refund's own status.
     */
    static String outcomeOf(JsonNode order, RefundAttempt attempt) {
        if ("REFUNDED".equals(text(order.path("paymentStatus")))) {
            return "SUCCESSFUL";
        }
        if (attempt.montonioRefundUuid() == null) {
            return null;
        }
        JsonNode refunds = order.path("refunds");
        for (int i = 0; i < refunds.size(); i++) {
            JsonNode refund = refunds.get(i);
            if (attempt.montonioRefundUuid().equals(text(refund.path("uuid")))) {
                String status = text(refund.path("status"));
                return switch (status == null ? "" : status) {
                    case "SUCCESSFUL", "REJECTED", "CANCELED" -> status;
                    default -> null; // PENDING or PROCESSING: not settled yet
                };
            }
        }
        return null;
    }

    /**
     * The order's open refund attempt, opened if there is none. The database allows
     * one open attempt per order, so two requests racing here end up sharing one
     * attempt and one idempotency key rather than sending Montonio two refunds.
     */
    private RefundAttempt currentAttempt(UUID orderId, StoredPayment payment) {
        for (int tries = 0; tries < 3; tries++) {
            Optional<RefundAttempt> open = store.unresolvedRefund(orderId);
            if (open.isPresent()) {
                return open.get();
            }
            String key = "refund-" + orderId + "-" + (store.refundAttempts(orderId) + 1);
            try {
                store.insertRefund(orderId, payment.montonioOrderUuid(), key, payment.amount(), payment.currency());
            } catch (DuplicateKeyException raced) {
                // Another request opened an attempt at the same moment; the next look finds it.
            }
        }
        throw AppException.conflict("This refund is already being handled. Try again in a moment.");
    }

    private JsonNode verified(String token) {
        JsonNode claims;
        try {
            claims = montonio.jwt().verify(token);
        } catch (MontonioJwt.InvalidTokenException e) {
            throw AppException.badRequest("Invalid payment notification");
        }
        if (!props.accessKey().equals(text(claims.path("accessKey")))) {
            throw AppException.badRequest("Invalid payment notification");
        }
        return claims;
    }

    private static boolean sameAmount(BigDecimal expected, JsonNode actual) {
        String value = text(actual);
        if (value == null) {
            return false;
        }
        try {
            return new BigDecimal(value).compareTo(expected) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
