package ee.shop.payment.web.dto;

import ee.shop.order.web.dto.DeliveryDetails;
import ee.shop.order.web.dto.OrderResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the customer sees after coming back from their bank.
 *
 * The page is reached with a payment token, not an account, so this carries no
 * name, email, phone or address: only what identifies the purchase to the person
 * who has just paid for it.
 */
public record BankLinkReturnResponse(
        String reference,
        /** PAID, FAILED, or PENDING while the bank has not confirmed yet. */
        String status,
        BigDecimal total,
        String currency,
        List<Line> lines,
        DeliveryDetails delivery
) {
    public record Line(String productName, int quantity) {}

    /**
     * Once the order has an outcome it wins over the token: an older token (a
     * PENDING one from a slow return, say) must not contradict a result the order
     * has already recorded.
     */
    public static BankLinkReturnResponse of(OrderResponse order, String resultStatus) {
        String status = switch (order.status()) {
            case "PAID", "SHIPPED", "REFUND_REQUESTED", "REFUNDED" -> "PAID";
            case "PAYMENT_FAILED", "CANCELLED" -> "FAILED";
            default -> resultStatus;
        };
        return new BankLinkReturnResponse(order.reference(), status, order.total(), order.currency(),
                order.lines().stream().map(line -> new Line(line.productName(), line.quantity())).toList(),
                order.delivery());
    }
}
