package ee.shop.order.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only (V12__orders.sql's trigger + revoked UPDATE/DELETE for
 * shop_app) — current order status is ALWAYS derived by folding these
 * events, never read from a mutable column (DECISIONS.md §1). Only CREATED
 * is ever written by this stage (checkout); PAID/PAYMENT_FAILED arrive with
 * Stripe + the RabbitMQ consumer, SHIPPED/REFUNDED with fulfilment and the
 * admin screens — the type is modeled now so status
 * derivation has real cases to fold over as those land, not just CREATED.
 */
@Entity
@Table(name = "order_events")
public class OrderEvent {

    public enum Type {
        CREATED, PAID, PAYMENT_FAILED, CANCELLED, SHIPPED, DELIVERED,
        /**
         * A refund the payment provider has accepted but not yet completed. Bank-link
         * refunds settle days later and can be rejected, so the order says what is
         * actually true in the meantime instead of REFUNDED.
         */
        REFUND_REQUESTED,
        REFUNDED,
        /**
         * Informational only: a billing document was (re)issued. Recorded so it
         * shows in the admin order timeline, but it must never become the
         * order's derived status — an order that is PAID and then has its
         * invoice reissued is still PAID, not "INVOICE_ISSUED".
         */
        INVOICE_ISSUED;

        /** Whether folding order_events should treat this event as the order's current status. */
        public boolean isStatus() {
            return this != INVOICE_ISSUED;
        }
    }

    /**
     * THE single implementation of "current status = the most recent event that
     * actually represents a status". order_events can carry other kinds of fact
     * (INVOICE_ISSUED today) that must be visible in the timeline without
     * corrupting what "current status" means. OrderService and AdminOrderService
     * both fold through here rather than each keeping a copy of this loop.
     */
    public static String deriveStatus(List<OrderEvent> chronologicalEvents) {
        for (int i = chronologicalEvents.size() - 1; i >= 0; i--) {
            Type type = chronologicalEvents.get(i).getEventType();
            if (type.isStatus()) {
                return type.name();
            }
        }
        return Type.CREATED.name();
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private Type eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected OrderEvent() {} // JPA

    public OrderEvent(UUID orderId, Type eventType) {
        this.orderId = orderId;
        this.eventType = eventType;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public Type getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
}
