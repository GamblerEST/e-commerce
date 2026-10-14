package ee.shop.payment.messaging;

import ee.shop.order.service.OrderService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Pure unit test: this class is a thin bridge, so all it needs to prove is that it routes to the right OrderService method. */
@Tag("unit")
class PaymentResultConsumerTest {

    private final OrderService orders = mock(OrderService.class);
    private final PaymentResultConsumer consumer = new PaymentResultConsumer(orders);

    @Test
    void succeeded_callsMarkPaid_notMarkPaymentFailed() {
        UUID orderId = UUID.randomUUID();

        consumer.handle(new PaymentResultMessage(orderId, true, null));

        verify(orders).markPaid(orderId);
        verify(orders, never()).markPaymentFailed(any(), anyString());
    }

    @Test
    void failed_callsMarkPaymentFailedWithTheReason_notMarkPaid() {
        UUID orderId = UUID.randomUUID();

        consumer.handle(new PaymentResultMessage(orderId, false, "insufficient_funds"));

        verify(orders).markPaymentFailed(orderId, "insufficient_funds");
        verify(orders, never()).markPaid(any());
    }
}
