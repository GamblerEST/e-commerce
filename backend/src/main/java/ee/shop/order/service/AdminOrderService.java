package ee.shop.order.service;

import ee.shop.common.error.AppException;
import ee.shop.common.mail.MailService;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.order.model.BillingDocument;
import ee.shop.order.model.Order;
import ee.shop.order.model.OrderEvent;
import ee.shop.order.repository.BillingDocumentRepository;
import ee.shop.order.repository.OrderEventRepository;
import ee.shop.order.repository.OrderRepository;
import ee.shop.order.web.dto.OrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Order management for staff. Deliberately separate from
 * OrderService's customer-facing methods, which scope every lookup to the
 * caller — here the whole point is seeing orders that aren't yours, so the
 * ownership filter is absent and the authorization has to carry the entire
 * weight. That is exactly why it lives in its own class behind its own
 * annotations rather than as a flag on the customer path, where forgetting to
 * pass `false` would silently expose every order to everyone.
 *
 * Status changes APPEND events; nothing here ever updates an order row
 * (DECISIONS.md §1). "Mark as shipped" is a new fact about the
 * order, not an edit to it.
 */
@Service
public class AdminOrderService {

    private final OrderRepository orders;
    private final OrderEventRepository orderEvents;
    private final OrderRefunds refunds;
    private final OrderService orderService;
    private final MailService mail;
    private final InvoiceService invoices;
    private final BillingDocumentRepository billingDocuments;
    private final UserRepository users;

    public AdminOrderService(OrderRepository orders, OrderEventRepository orderEvents,
                              OrderRefunds refunds, OrderService orderService, MailService mail,
                              InvoiceService invoices, BillingDocumentRepository billingDocuments,
                              UserRepository users) {
        this.orders = orders;
        this.orderEvents = orderEvents;
        this.refunds = refunds;
        this.orderService = orderService;
        this.mail = mail;
        this.invoices = invoices;
        this.billingDocuments = billingDocuments;
        this.users = users;
    }

    /** Every order, newest first — optionally narrowed by derived status and date. */
    @Transactional(readOnly = true)
    public List<AdminOrderSummary> list(String statusFilter, Instant from, Instant to) {
        List<Order> matched = orders.findAllByOrderByCreatedAtDesc().stream()
                .filter(o -> from == null || !o.getCreatedAt().isBefore(from))
                .filter(o -> to == null || !o.getCreatedAt().isAfter(to))
                .toList();

        // Status is derived, so it can only be filtered after folding events —
        // same constraint (and the same fix) as the customer-facing list.
        Map<UUID, String> statuses = deriveStatuses(matched.stream().map(Order::getId).toList());

        return matched.stream()
                .map(o -> new AdminOrderSummary(
                        o.getId(), OrderResponse.referenceFor(o.getId()), statuses.get(o.getId()),
                        o.getBillingName(), o.getBillingEmail(), o.getTotal(), o.getCurrency(),
                        o.getDeliveryName(), o.getCreatedAt()))
                .filter(s -> statusFilter == null || statusFilter.equalsIgnoreCase(s.status()))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse detail(UUID orderId) {
        // Reuses the customer-facing projection deliberately: staff should see
        // exactly the order the customer sees, not a parallel shape that could
        // drift and make a support conversation confusing.
        return orderService.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));
    }

    /**
     * Marks an order shipped. Only a PAID order can ship — shipping something
     * unpaid, cancelled or refunded is a warehouse mistake the system should
     * refuse rather than faithfully record.
     */
    @Transactional
    public OrderResponse markShipped(UUID orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> AppException.notFound("Order not found"));
        String status = currentStatus(orderId);
        if (!OrderEvent.Type.PAID.name().equals(status)) {
            throw AppException.conflict("Only a paid order can be marked shipped (this one is " + status + ")");
        }
        orderEvents.save(new OrderEvent(orderId, OrderEvent.Type.SHIPPED));
        OrderResponse shipped = orderService.findById(order.getId()).orElseThrow();
        // No attachment: the invoice already went out with the payment confirmation.
        mail.send(shipped.billingEmail(), "Your order has shipped",
                "Good news — your order " + shipped.reference() + " is on its way.\n\n"
                        + OrderMailFormatter.summary(shipped),
                "order-shipped");
        return shipped;
    }

    /**
     * Marks a shipped order delivered. Only SHIPPED can be delivered: an order
     * cannot arrive before it left, and "delivered" is the last step of the
     * physical journey, so it is reached only from there.
     */
    @Transactional
    public OrderResponse markDelivered(UUID orderId) {
        orders.findById(orderId).orElseThrow(() -> AppException.notFound("Order not found"));
        String status = currentStatus(orderId);
        if (!OrderEvent.Type.SHIPPED.name().equals(status)) {
            throw AppException.conflict("Only a shipped order can be marked delivered (this one is " + status + ")");
        }
        orderEvents.save(new OrderEvent(orderId, OrderEvent.Type.DELIVERED));
        OrderResponse delivered = orderService.findById(orderId).orElseThrow();
        mail.send(delivered.billingEmail(), "Your order has been delivered",
                "Your order " + delivered.reference() + " has been delivered. We hope you enjoy it!\n\n"
                        + OrderMailFormatter.summary(delivered)
                        + "\n\nYou can now review what you bought from My Orders. If anything is wrong, "
                        + "just reply to this email.",
                "order-delivered");
        return delivered;
    }

    /**
     * Staff-initiated refund. Same mechanics as the customer's own
     * cancel-after-payment path (a real refund through whichever provider took
     * the payment, then an event recording it) — but reachable for an order the
     * caller does not own, which is the whole reason support exists.
     *
     * A card refund is REFUNDED at once. A bank-link refund is REFUND_REQUESTED
     * until Montonio settles it (BankLinkRefundReconciler), and while it is, the
     * status check below refuses a second one.
     *
     * Deliberately NOT @Transactional at this level: calls to a payment provider
     * must not run inside an open database transaction. Each step below is
     * independently atomic and idempotent, so a crash between them leaves a
     * recoverable state rather than a half-refund.
     */
    public OrderResponse refund(UUID orderId) {
        orders.findById(orderId).orElseThrow(() -> AppException.notFound("Order not found"));
        String status = currentStatus(orderId);
        if (!REFUNDABLE_STATUSES.contains(status)) {
            throw AppException.conflict(
                    "Only a paid, shipped or delivered order can be refunded (this one is " + status + ")");
        }
        OrderRefunds.Outcome outcome = refunds.refund(orderId);
        boolean settled = outcome == OrderRefunds.Outcome.REFUNDED;
        orderEvents.save(new OrderEvent(orderId, settled
                ? OrderEvent.Type.REFUNDED
                : OrderEvent.Type.REFUND_REQUESTED));
        OrderResponse refunded = orderService.findById(orderId).orElseThrow();
        mail.send(refunded.billingEmail(),
                settled ? "Your refund was processed" : "Your refund was requested",
                (settled ? "We have refunded " : "We have asked your bank to refund ")
                        + refunded.total() + " " + refunded.currency() + " for order "
                        + refunded.reference() + ".\n\n" + OrderMailFormatter.summary(refunded)
                        + "\n\nThe money should reach your original payment method within a few business "
                        + "days, depending on your bank.",
                settled ? "order-refunded" : "order-refund-requested");
        return refunded;
    }

    /**
     * Statuses an order can only reach by having been paid at some point
     * (SHIPPED needs a prior PAID, DELIVERED a prior SHIPPED, REFUNDED a prior PAID, SHIPPED or DELIVERED). CREATED,
     * PAYMENT_FAILED and CANCELLED were never billed, so there is nothing to issue.
     */
    private static final Set<String> BILLED_STATUSES = Set.of(
            OrderEvent.Type.PAID.name(), OrderEvent.Type.SHIPPED.name(),
            OrderEvent.Type.DELIVERED.name(), OrderEvent.Type.REFUND_REQUESTED.name(),
            OrderEvent.Type.REFUNDED.name());

    /** A delivered order can still come back (a withdrawal, a fault), so it stays refundable. */
    private static final Set<String> REFUNDABLE_STATUSES = Set.of(
            OrderEvent.Type.PAID.name(), OrderEvent.Type.SHIPPED.name(), OrderEvent.Type.DELIVERED.name());

    @Transactional(readOnly = true)
    public List<BillingDocumentView> billingDocuments(UUID orderId) {
        orders.findById(orderId).orElseThrow(() -> AppException.notFound("Order not found"));
        return billingDocuments.findByOrderIdOrderByGeneratedAt(orderId).stream()
                .map(d -> new BillingDocumentView(d.getId(), d.getGeneratedAt(), issuedBy(d.getGeneratedByUserId())))
                .toList();
    }

    private String issuedBy(UUID userId) {
        if (userId == null) {
            return "System (issued automatically at payment)";
        }
        return users.findById(userId).map(User::getEmail).orElse("(removed account)");
    }

    /** Only ever finds a document belonging to THAT order, so an id from another order is a 404. */
    @Transactional(readOnly = true)
    public BillingFile downloadBillingDocument(UUID orderId, UUID documentId) {
        BillingDocument doc = billingDocuments.findById(documentId)
                .filter(d -> d.getOrderId().equals(orderId))
                .orElseThrow(() -> AppException.notFound("Billing document not found"));
        try {
            return new BillingFile(OrderResponse.referenceFor(orderId) + "-" + doc.getId() + ".pdf",
                    invoices.readFile(doc));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the stored billing document", e);
        }
    }

    /**
     * Issues a NEW billing document and emails it. Earlier ones are never touched.
     * Does not require one to exist already: this is also the way to recover when
     * the automatic one at payment time could not be produced.
     */
    @Transactional
    public OrderResponse reissueBillingDocument(UUID orderId, UUID adminUserId) {
        orders.findById(orderId).orElseThrow(() -> AppException.notFound("Order not found"));
        String status = currentStatus(orderId);
        if (!BILLED_STATUSES.contains(status)) {
            throw AppException.conflict(
                    "Only an order that was actually paid can have a billing document issued (this one is "
                            + status + ")");
        }
        BillingDocument document;
        try {
            document = invoices.reissue(orderId, adminUserId);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the billing document", e);
        }
        OrderResponse order = orderService.findById(orderId).orElseThrow();
        String body = "A new billing document for order " + order.reference() + " has been issued. "
                + "It replaces nothing: earlier versions stay on file.\n\n" + OrderMailFormatter.summary(order);
        try {
            mail.sendWithAttachment(order.billingEmail(), "Your billing document for order " + order.reference(),
                    body + "\n\nThe document is attached.", "invoice-" + order.reference() + ".pdf",
                    invoices.readFile(document), "application/pdf", "invoice-reissued");
        } catch (IOException e) {
            // The document exists and is downloadable; only the attachment failed.
            mail.send(order.billingEmail(), "Your billing document for order " + order.reference(), body,
                    "invoice-reissued");
        }
        return order;
    }

    private String currentStatus(UUID orderId) {
        return OrderEvent.deriveStatus(orderEvents.findByOrderIdOrderByOccurredAt(orderId));
    }

    private Map<UUID, String> deriveStatuses(List<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<OrderEvent>> byOrder = orderEvents.findByOrderIdInOrderByOccurredAt(orderIds).stream()
                .collect(Collectors.groupingBy(OrderEvent::getOrderId));
        return orderIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> OrderEvent.deriveStatus(byOrder.getOrDefault(id, List.of()))));
    }

    public record BillingDocumentView(UUID id, Instant generatedAt, String issuedBy) {}

    public record BillingFile(String filename, byte[] bytes) {}

    /** Staff list row — carries the customer's name/email, which the public order list never does. */
    public record AdminOrderSummary(
            UUID id, String reference, String status,
            String customerName, String customerEmail,
            java.math.BigDecimal total, String currency,
            String deliveryName, Instant createdAt) {}
}
