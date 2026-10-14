package ee.shop.order.service;

import ee.shop.order.web.dto.OrderLineResponse;
import ee.shop.order.web.dto.OrderResponse;

/**
 * The line-item and total block shared by every order email (confirmation,
 * payment received, payment failed, shipped, refunded). One implementation, so
 * a customer gets the same breakdown whichever stage the order is at, instead of
 * only the first email having one.
 */
final class OrderMailFormatter {

    private OrderMailFormatter() {}

    static String summary(OrderResponse order) {
        StringBuilder body = new StringBuilder();
        for (OrderLineResponse line : order.lines()) {
            body.append(line.productName()).append(" x").append(line.quantity())
                    .append(" — ").append(line.lineTotal()).append(' ').append(line.currency()).append('\n');
        }
        if (order.delivery() != null) {
            body.append("Delivery: ").append(order.delivery().name()).append('\n');
        }
        body.append("\nTotal: ").append(order.total()).append(' ').append(order.currency());
        return body.toString();
    }
}
