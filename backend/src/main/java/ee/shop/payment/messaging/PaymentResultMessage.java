package ee.shop.payment.messaging;

import java.util.UUID;

/** Published by StripeWebhookController, consumed by PaymentResultConsumer. */
public record PaymentResultMessage(UUID orderId, boolean succeeded, String failureReason) {}
