package ee.shop.payment.web;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import ee.shop.common.error.AppException;
import ee.shop.payment.messaging.PaymentResultMessage;
import ee.shop.payment.repository.ProcessedStripeEventRepository;
import ee.shop.payment.service.PaymentService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static ee.shop.config.RabbitConfig.PAYMENT_EXCHANGE;
import static ee.shop.config.RabbitConfig.PAYMENT_RESULT_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests of the atomic-claim-then-publish design.
 * PaymentService and the repositories are mocked, so what's actually
 * under test is CONTROL FLOW: dedup wins the race, only the two relevant
 * event types get published, and an event for an unrecognized PaymentIntent
 * is rejected rather than silently dropped.
 */
@Tag("security")
class StripeWebhookControllerTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final ProcessedStripeEventRepository processedEvents = mock(ProcessedStripeEventRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final StripeWebhookController controller =
            new StripeWebhookController(paymentService, processedEvents, rabbitTemplate);

    private Event eventFor(String type, String eventId, PaymentIntent intent) {
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.ofNullable(intent));

        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn(type);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }

    @Test
    void succeededEvent_publishesPaymentResultMessage() {
        UUID orderId = UUID.randomUUID();
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn("pi_1");
        Event event = eventFor("payment_intent.succeeded", "evt_1", intent);

        when(paymentService.verifyAndParseWebhook("payload", "sig")).thenReturn(event);
        when(paymentService.findOrderIdForPaymentIntent("pi_1")).thenReturn(Optional.of(orderId));

        controller.webhook("payload", "sig");

        verify(rabbitTemplate).convertAndSend(eq(PAYMENT_EXCHANGE), eq(PAYMENT_RESULT_ROUTING_KEY),
                eq(new PaymentResultMessage(orderId, true, null)));
    }

    @Test
    void failedEvent_publishesWithTheDeclineReasonFromStripe() {
        UUID orderId = UUID.randomUUID();
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn("pi_2");
        StripeError error = mock(StripeError.class);
        when(error.getMessage()).thenReturn("Your card was declined.");
        when(intent.getLastPaymentError()).thenReturn(error);
        Event event = eventFor("payment_intent.payment_failed", "evt_2", intent);

        when(paymentService.verifyAndParseWebhook("payload", "sig")).thenReturn(event);
        when(paymentService.findOrderIdForPaymentIntent("pi_2")).thenReturn(Optional.of(orderId));

        controller.webhook("payload", "sig");

        verify(rabbitTemplate).convertAndSend(eq(PAYMENT_EXCHANGE), eq(PAYMENT_RESULT_ROUTING_KEY),
                eq(new PaymentResultMessage(orderId, false, "Your card was declined.")));
    }

    @Test
    void alreadyProcessedEvent_doesNotPublishAgain_ratherThanDoubleFulfilling() {
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn("pi_3");
        Event event = eventFor("payment_intent.succeeded", "evt_3", intent);
        when(paymentService.verifyAndParseWebhook("payload", "sig")).thenReturn(event);
        // Stripe's own delivery guarantee is at-least-once, so a replay here
        // is expected traffic, not an edge case — see StripeWebhookController.
        doThrow(new DataIntegrityViolationException("duplicate")).when(processedEvents).save(any());

        controller.webhook("payload", "sig");

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void irrelevantEventType_isAckedWithoutPublishing() {
        Event event = eventFor("charge.refunded", "evt_4", null);
        when(paymentService.verifyAndParseWebhook("payload", "sig")).thenReturn(event);

        controller.webhook("payload", "sig");

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void paymentIntentWithNoMatchingOrder_isRejected_notSilentlyDropped() {
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn("pi_orphan");
        Event event = eventFor("payment_intent.succeeded", "evt_orphan", intent);

        when(paymentService.verifyAndParseWebhook("payload", "sig")).thenReturn(event);
        when(paymentService.findOrderIdForPaymentIntent("pi_orphan")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.webhook("payload", "sig"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus().value())
                .isEqualTo(400);

        verifyNoInteractions(rabbitTemplate);
    }
}
