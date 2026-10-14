package ee.shop.order.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static ee.shop.order.model.OrderEvent.Type.CREATED;
import static ee.shop.order.model.OrderEvent.Type.INVOICE_ISSUED;
import static ee.shop.order.model.OrderEvent.Type.PAID;
import static ee.shop.order.model.OrderEvent.Type.PAYMENT_FAILED;
import static ee.shop.order.model.OrderEvent.Type.REFUNDED;
import static ee.shop.order.model.OrderEvent.Type.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every order-status read in the app folds through OrderEvent.deriveStatus, so
 * the rule that INVOICE_ISSUED is a fact about the order and never its status
 * is pinned down here directly rather than only through the HTTP layer.
 */
@Tag("unit")
class OrderEventTest {

    private static final UUID ORDER = UUID.randomUUID();

    private static List<OrderEvent> events(OrderEvent.Type... types) {
        return java.util.Arrays.stream(types).map(t -> new OrderEvent(ORDER, t)).toList();
    }

    @Test
    void noEventsMeansCreated() {
        assertThat(OrderEvent.deriveStatus(List.of())).isEqualTo("CREATED");
    }

    @Test
    void theLastEventIsTheStatus() {
        assertThat(OrderEvent.deriveStatus(events(CREATED, PAID, SHIPPED))).isEqualTo("SHIPPED");
        assertThat(OrderEvent.deriveStatus(events(CREATED, PAYMENT_FAILED))).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void aReissuedInvoiceDoesNotChangeAPaidOrdersStatus() {
        assertThat(OrderEvent.deriveStatus(events(CREATED, PAID, INVOICE_ISSUED))).isEqualTo("PAID");
    }

    @Test
    void invoiceEventsInterleavedWithStatusesAreSkippedEachTime() {
        assertThat(OrderEvent.deriveStatus(events(CREATED, PAID, INVOICE_ISSUED, SHIPPED, INVOICE_ISSUED)))
                .isEqualTo("SHIPPED");
        assertThat(OrderEvent.deriveStatus(events(CREATED, PAID, INVOICE_ISSUED, REFUNDED, INVOICE_ISSUED, INVOICE_ISSUED)))
                .isEqualTo("REFUNDED");
    }

    @Test
    void onlyInvoiceEventsFallsBackToCreated() {
        assertThat(OrderEvent.deriveStatus(events(INVOICE_ISSUED))).isEqualTo("CREATED");
    }

    @Test
    void onlyInvoiceIssuedIsNotAStatus() {
        for (OrderEvent.Type type : OrderEvent.Type.values()) {
            assertThat(type.isStatus()).isEqualTo(type != INVOICE_ISSUED);
        }
    }
}
