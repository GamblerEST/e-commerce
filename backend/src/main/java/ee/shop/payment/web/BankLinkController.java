package ee.shop.payment.web;

import ee.shop.common.error.AppException;
import ee.shop.common.security.RateLimitService;
import ee.shop.order.service.BankLinkRefundReconciler;
import ee.shop.order.service.OrderService;
import ee.shop.payment.banklink.BankLinkPaymentService;
import ee.shop.payment.service.PaymentService;
import ee.shop.payment.web.dto.BankLinkNotificationRequest;
import ee.shop.payment.web.dto.BankLinkReturnRequest;
import ee.shop.payment.web.dto.BankLinkReturnResponse;
import ee.shop.payment.web.dto.PaymentMethodsResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Payment choices for checkout, and the two ways a bank-link result comes back:
 * Montonio's notification, and the customer returning from their bank. Both carry
 * the same signed token and are handled identically, so whichever arrives first
 * settles the order and the other changes nothing.
 */
@RestController
@RequestMapping("/api/payments")
public class BankLinkController {

    private final PaymentService cards;
    private final BankLinkPaymentService bankLinks;
    private final BankLinkRefundReconciler refundReconciler;
    private final OrderService orderService;
    private final RateLimitService rateLimit;

    public BankLinkController(PaymentService cards, BankLinkPaymentService bankLinks,
                              BankLinkRefundReconciler refundReconciler, OrderService orderService,
                              RateLimitService rateLimit) {
        this.cards = cards;
        this.bankLinks = bankLinks;
        this.refundReconciler = refundReconciler;
        this.orderService = orderService;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/methods")
    public PaymentMethodsResponse methods() {
        List<PaymentMethodsResponse.Bank> banks = List.of();
        if (bankLinks.isEnabled()) {
            try {
                banks = bankLinks.banks().stream()
                        .map(bank -> new PaymentMethodsResponse.Bank(bank.code(), bank.name()))
                        .toList();
            } catch (AppException unreachable) {
                // Checkout offers what still works rather than failing the whole page.
            }
        }
        return new PaymentMethodsResponse(cards.isEnabled(), !banks.isEmpty(), banks);
    }

    /**
     * Montonio retries anything but a 2xx for two days, so the answers matter: a
     * duplicate is a 200, a token that fails verification is a 400 (a retry cannot
     * fix it), and a result that could not be queued is an error, so it comes again.
     */
    @PostMapping("/bank-link/notification")
    @ResponseStatus(HttpStatus.OK)
    public void notification(@Valid @RequestBody BankLinkNotificationRequest body) {
        if (body.orderToken() != null) {
            bankLinks.acceptResult(body.orderToken());
        } else if (body.refundToken() != null) {
            bankLinks.verifyRefundNotification(body.refundToken());
            refundReconciler.reconcile();
        } else {
            throw AppException.badRequest("Invalid payment notification");
        }
    }

    @PostMapping("/bank-link/return")
    public BankLinkReturnResponse returnFromBank(@Valid @RequestBody BankLinkReturnRequest body,
                                                 HttpServletRequest http) {
        rateLimit.enforceBankLinkReturn(http.getRemoteAddr());
        BankLinkPaymentService.AcceptedResult result = bankLinks.acceptResult(body.orderToken());
        return orderService.findById(result.orderId())
                .map(order -> BankLinkReturnResponse.of(order, result.status()))
                .orElseThrow(() -> AppException.notFound("Order not found"));
    }
}
