package ee.shop.payment.web;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import ee.shop.common.error.AppException;
import ee.shop.payment.messaging.PaymentResultMessage;
import ee.shop.payment.model.ProcessedStripeEvent;
import ee.shop.payment.repository.ProcessedStripeEventRepository;
import ee.shop.payment.service.PaymentService;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static ee.shop.config.RabbitConfig.PAYMENT_EXCHANGE;
import static ee.shop.config.RabbitConfig.PAYMENT_RESULT_ROUTING_KEY;

/**
 * Stripe calls this — never the frontend, never with anything the browser
 * controlled. The Stripe-Signature header is the ONLY reason any of this
 * payload can be trusted (PaymentService.verifyAndParseWebhook); without
 * it, this endpoint would just let anyone POST "payment succeeded" for an
 * order they never paid for.
 *
 * Deliberately thin: verify, dedupe, publish to the queue, return fast.
 * Stripe expects a quick 2xx or it treats the delivery as failed and
 * retries — the actual order/inventory/email work happens asynchronously
 * in PaymentResultConsumer, which is the whole reason the payment result
 * travels through a queue rather than being handled inline here.
 */
@RestController
@RequestMapping("/api/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentService paymentService;
    private final ProcessedStripeEventRepository processedEvents;
    private final RabbitTemplate rabbitTemplate;

    public StripeWebhookController(PaymentService paymentService, ProcessedStripeEventRepository processedEvents,
                                    RabbitTemplate rabbitTemplate) {
        this.paymentService = paymentService;
        this.processedEvents = processedEvents;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void webhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String signatureHeader) {
        Event event = paymentService.verifyAndParseWebhook(payload, signatureHeader);

        // Atomic claim (same "insert, unique constraint, catch conflict"
        // idiom as orders.idempotency_key) — Stripe's own docs guarantee
        // AT-LEAST-once delivery, so a replayed event is expected, normal
        // traffic, not an edge case. Losing the race here means it's
        // already been queued once; ack and stop, don't publish again.
        try {
            processedEvents.save(new ProcessedStripeEvent(event.getId()));
        } catch (DataIntegrityViolationException e) {
            log.info("Stripe event {} already processed, skipping", event.getId());
            return;
        }

        if (!"payment_intent.succeeded".equals(event.getType())
                && !"payment_intent.payment_failed".equals(event.getType())) {
            return; // we only care about these two; anything else is a silent no-op ack
        }

        // getObject() only deserializes when the event's api_version exactly
        // matches the version stripe-java was built against — a live account
        // (or the Stripe CLI, which forwards at whatever version the CLI
        // itself defaults to) can easily be one version ahead, and getObject()
        // then silently returns empty rather than deserializing anyway.
        // deserializeUnsafe() is Stripe's own documented fallback for exactly
        // that drift: parse from the raw JSON regardless of version, which is
        // safe here because PaymentIntent's shape (id, status, last_payment_error)
        // has been stable across versions for years.
        StripeObject dataObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (dataObject == null) {
            try {
                dataObject = event.getDataObjectDeserializer().deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                log.warn("Stripe event {} ({}) payload could not be deserialized even unsafely: {}",
                        event.getId(), event.getType(), e.getMessage());
                return;
            }
        }
        if (!(dataObject instanceof PaymentIntent intent)) {
            log.warn("Stripe event {} ({}) had no deserializable PaymentIntent payload", event.getId(), event.getType());
            return;
        }

        UUID orderId = paymentService.findOrderIdForPaymentIntent(intent.getId())
                .orElseThrow(() -> {
                    log.warn("Stripe PaymentIntent {} has no matching order — event {}", intent.getId(), event.getId());
                    // 400, not 200: this IS unexpected (every PaymentIntent we
                    // create is immediately linked to an order) — a 4xx tells
                    // Stripe not to keep retrying something that will never
                    // resolve, while still surfacing it in their dashboard.
                    return AppException.badRequest("No order found for this PaymentIntent");
                });

        boolean succeeded = "payment_intent.succeeded".equals(event.getType());
        String failureReason = succeeded ? null : classifyFailure(intent.getLastPaymentError());

        rabbitTemplate.convertAndSend(PAYMENT_EXCHANGE, PAYMENT_RESULT_ROUTING_KEY,
                new PaymentResultMessage(orderId, succeeded, failureReason));
    }

    /**
     * Maps Stripe's decline/error codes onto the four scenarios this shop
     * calls out by name (insufficient funds, invalid card number, expired
     * card, gateway timeout) rather than surfacing Stripe's raw message
     * verbatim — this text ends up in the payment-failed email and the
     * order's audit trail, so it should say WHY in terms a customer (and a
     * operator checking each scenario) recognizes, not Stripe's internal
     * wording. Falls back to Stripe's own message for anything else Stripe
     * declines for, rather than inventing a category that doesn't fit.
     */
    private static String classifyFailure(StripeError error) {
        if (error == null) {
            return "Payment failed";
        }
        String declineCode = error.getDeclineCode();
        String code = error.getCode();
        String type = error.getType();

        if ("insufficient_funds".equals(declineCode)) {
            return "Your card has insufficient funds.";
        }
        if ("expired_card".equals(declineCode) || "expired_card".equals(code)) {
            return "Your card has expired.";
        }
        if ("incorrect_number".equals(code) || "invalid_number".equals(code)
                || "invalid_card_number".equals(declineCode)) {
            return "Your card number is invalid.";
        }
        if ("processing_error".equals(code) || "api_connection_error".equals(type)
                || "api_error".equals(type)) {
            return "The payment gateway timed out. Please try again.";
        }
        return error.getMessage() != null ? error.getMessage() : "Payment failed";
    }
}
