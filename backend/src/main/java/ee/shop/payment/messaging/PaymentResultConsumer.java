package ee.shop.payment.messaging;

import ee.shop.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static ee.shop.config.RabbitConfig.PAYMENT_RESULT_QUEUE;

/**
 * Consumes what StripeWebhookController publishes.
 * Deliberately dumb — OrderService.markPaid/markPaymentFailed own all the
 * actual state-transition and idempotency logic, since they're the exact
 * same methods a redelivered/retried message must be able to call safely.
 * This class only bridges the queue to that call.
 *
 * A message that throws out of this listener trips RabbitConfig's retry
 * (3 attempts, exponential backoff); on exhaustion,
 * RejectAndDontRequeueRecoverer sends it to payment.result.dlq instead of
 * retrying forever or silently dropping it.
 */
@Component
public class PaymentResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultConsumer.class);

    private final OrderService orders;

    public PaymentResultConsumer(OrderService orders) {
        this.orders = orders;
    }

    @RabbitListener(queues = PAYMENT_RESULT_QUEUE)
    public void handle(PaymentResultMessage message) {
        if (message.succeeded()) {
            orders.markPaid(message.orderId());
        } else {
            orders.markPaymentFailed(message.orderId(), message.failureReason());
        }
        log.info("Processed payment result for order {}: succeeded={}", message.orderId(), message.succeeded());
    }
}
