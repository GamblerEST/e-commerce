package ee.shop.order.service;

import ee.shop.common.audit.AuditService;
import ee.shop.order.model.OrderEvent;
import ee.shop.order.repository.OrderEventRepository;
import ee.shop.payment.banklink.BankLinkPaymentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Finishes bank-link refunds once Montonio has.
 *
 * A successful refund appends REFUNDED. A rejected or cancelled one puts the order
 * back to the status it had before the refund was requested, because the money
 * never moved, and records the failure where staff will see it.
 *
 * Runs on a schedule and whenever Montonio sends a refund notification, so a
 * missed notification delays a refund's status but never loses it.
 */
@Service
public class BankLinkRefundReconciler {

    private final BankLinkPaymentService bankLinks;
    private final OrderEventRepository orderEvents;
    private final AuditService audit;

    public BankLinkRefundReconciler(BankLinkPaymentService bankLinks, OrderEventRepository orderEvents,
                                    AuditService audit) {
        this.bankLinks = bankLinks;
        this.orderEvents = orderEvents;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.montonio.refund-check-interval}",
            initialDelayString = "${app.montonio.refund-check-interval}")
    public void onSchedule() {
        if (bankLinks.isEnabled()) {
            reconcile();
        }
    }

    public void reconcile() {
        for (BankLinkPaymentService.SettledRefund settled : bankLinks.settleFinishedRefunds()) {
            List<OrderEvent> events = orderEvents.findByOrderIdOrderByOccurredAt(settled.orderId());
            // Only an order still waiting on this refund changes. Judged by the derived
            // status, so an informational event (an invoice) after the request cannot
            // hide that the order is still waiting.
            if (!OrderEvent.Type.REFUND_REQUESTED.name().equals(OrderEvent.deriveStatus(events))) {
                continue;
            }
            if (settled.successful()) {
                orderEvents.save(new OrderEvent(settled.orderId(), OrderEvent.Type.REFUNDED));
            } else {
                orderEvents.save(new OrderEvent(settled.orderId(), statusBeforeRefund(events)));
                audit.record("payment.bank_link_refund_" + settled.outcome().toLowerCase(Locale.ROOT), null,
                        "order " + settled.orderId(), null, null);
            }
        }
    }

    /**
     * The newest STATUS event that is not a refund request. Two requests landing
     * together both append one, and reverting to the event just before the last
     * would put the order back to "refund requested" instead of what it really was.
     * Informational events (an invoice) are not statuses and are never reverted to:
     * repeating one would leave the order stuck at "refund requested".
     */
    private static OrderEvent.Type statusBeforeRefund(List<OrderEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            OrderEvent.Type type = events.get(i).getEventType();
            if (type != OrderEvent.Type.REFUND_REQUESTED && type.isStatus()) {
                return type;
            }
        }
        return OrderEvent.Type.PAID;
    }
}
