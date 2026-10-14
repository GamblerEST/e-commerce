package ee.shop.order.service;

import ee.shop.payment.banklink.BankLinkPaymentService;
import ee.shop.payment.service.PaymentService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Refunds an order through whichever provider took its payment. */
@Service
public class OrderRefunds {

    public enum Outcome {
        /** The money is on its way back now. */
        REFUNDED,
        /** The provider accepted the refund and will settle it later; it can still be rejected. */
        REQUESTED
    }

    private final PaymentService cards;
    private final BankLinkPaymentService bankLinks;

    public OrderRefunds(PaymentService cards, BankLinkPaymentService bankLinks) {
        this.cards = cards;
        this.bankLinks = bankLinks;
    }

    public Outcome refund(UUID orderId) {
        if (bankLinks.paidByBankLink(orderId)) {
            bankLinks.requestRefund(orderId);
            return Outcome.REQUESTED;
        }
        cards.refundForOrder(orderId);
        return Outcome.REFUNDED;
    }
}
