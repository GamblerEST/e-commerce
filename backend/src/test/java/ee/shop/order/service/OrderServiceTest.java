package ee.shop.order.service;

import ee.shop.cart.web.dto.AddCartItemRequest;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers OrderService.markPaid/markPaymentFailed against
 * a REAL Postgres — these are the methods PaymentResultConsumer calls once a
 * Stripe webhook is verified. What matters here is the atomic inventory
 * claim (on_hand/reserved via InventoryService) and the idempotency guard —
 * RabbitMQ's at-least-once delivery means a redelivered message is expected
 * traffic, not an edge case, and neither of those is provable with a mocked
 * InventoryService.
 */
@Tag("unit")
@Tag("api")
@AutoConfigureMockMvc
class OrderServiceTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String GUEST_CART_COOKIE = "guest_cart_id";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orderService;

    private UUID insertProduct(String name, BigDecimal price, int onHand) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency, vat_category) VALUES (?, ?, ?, ?, 'EUR', 'STANDARD')",
                id, name, "regression-" + id, price);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    private Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie '%s' present on response", name).isNotNull();
        return c;
    }

    private OrderResponse checkoutOneUnit(UUID productId) throws Exception {
        Cookie csrf = cookie(mvc.perform(get("/api/cart")).andReturn(), CSRF_COOKIE);
        MvcResult addResult = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie guestCart = cookie(addResult, GUEST_CART_COOKIE);

        var billing = new CheckoutRequest("Mark Paid Tester", "markpaid@test.ee", "+372 5555 5555", "Pikk tn 1", null,
                "Tallinn", "10123", "EE", null, null, null, null);
        MvcResult result = mvc.perform(post("/api/checkout")
                        .cookie(csrf, guestCart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);
    }

    @Test
    void markPaid_convertsReservationToSold_andAppendsPaidEvent() throws Exception {
        UUID productId = insertProduct("Mark Paid Widget", new BigDecimal("30.00"), 5);
        OrderResponse order = checkoutOneUnit(productId);

        orderService.markPaid(order.id());

        // Not "the last event" — markPaid also issues an invoice, which appends
        // its own INVOICE_ISSUED event right after. This checks the specific
        // fact this test is named for: that a PAID event exists at all.
        Integer paidEvents = jdbc.queryForObject(
                "SELECT count(*) FROM order_events WHERE order_id = ?::uuid AND event_type = 'PAID'",
                Integer.class, order.id());
        assertThat(paidEvents).isEqualTo(1);

        Integer onHand = jdbc.queryForObject("SELECT on_hand FROM inventory WHERE product_id = ?", Integer.class, productId);
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(onHand).isEqualTo(4); // 5 on hand - 1 sold
        assertThat(reserved).isEqualTo(0);
    }

    @Test
    void markPaid_isIdempotent_aRedeliveredMessageDoesNotDoubleConvert() throws Exception {
        UUID productId = insertProduct("Redelivered Widget", new BigDecimal("30.00"), 5);
        OrderResponse order = checkoutOneUnit(productId);

        orderService.markPaid(order.id());
        orderService.markPaid(order.id()); // simulates RabbitMQ redelivering the same message

        Integer paidEventCount = jdbc.queryForObject(
                "SELECT count(*) FROM order_events WHERE order_id = ?::uuid AND event_type = 'PAID'",
                Integer.class, order.id());
        assertThat(paidEventCount).isEqualTo(1);

        Integer onHand = jdbc.queryForObject("SELECT on_hand FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(onHand).isEqualTo(4); // not decremented a second time
        assertThat(paymentsForClosedOrder(order.id()))
                .as("a redelivery to an order that is already paid is normal traffic, nothing for staff")
                .isZero();
    }

    @Test
    void markPaymentFailed_releasesReservation_andAppendsPaymentFailedEvent() throws Exception {
        UUID productId = insertProduct("Declined Widget", new BigDecimal("30.00"), 5);
        OrderResponse order = checkoutOneUnit(productId);

        orderService.markPaymentFailed(order.id(), "Your card was declined.");

        String eventType = jdbc.queryForObject(
                "SELECT event_type FROM order_events WHERE order_id = ?::uuid ORDER BY occurred_at DESC LIMIT 1",
                String.class, order.id());
        assertThat(eventType).isEqualTo("PAYMENT_FAILED");

        Integer onHand = jdbc.queryForObject("SELECT on_hand FROM inventory WHERE product_id = ?", Integer.class, productId);
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(onHand).isEqualTo(5); // stock returned — never actually sold
        assertThat(reserved).isEqualTo(0);
    }

    @Test
    void markPaid_afterAlreadyResolved_isANoOp_butTellsStaffAboutTheMoney() throws Exception {
        UUID productId = insertProduct("Already Failed Widget", new BigDecimal("30.00"), 5);
        OrderResponse order = checkoutOneUnit(productId);

        // Order already resolved as PAYMENT_FAILED (stock released back) —
        // an out-of-order/duplicate "succeeded" delivery must not resurrect
        // it into PAID and re-sell stock that's already been given back.
        orderService.markPaymentFailed(order.id(), "declined");
        orderService.markPaid(order.id());

        String eventType = jdbc.queryForObject(
                "SELECT event_type FROM order_events WHERE order_id = ?::uuid ORDER BY occurred_at DESC LIMIT 1",
                String.class, order.id());
        assertThat(eventType).isEqualTo("PAYMENT_FAILED");

        Integer onHand = jdbc.queryForObject("SELECT on_hand FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(onHand).isEqualTo(5);

        // The customer was still charged; the order cannot take the money, so a
        // person has to return it.
        assertThat(paymentsForClosedOrder(order.id())).isEqualTo(1);
    }

    @Test
    void markPaid_forACancelledOrder_leavesItCancelled_andTellsStaffAboutTheMoney() throws Exception {
        UUID productId = insertProduct("Cancelled Widget", new BigDecimal("30.00"), 5);
        OrderResponse order = checkoutOneUnit(productId);
        // Cancelled while the customer was still paying.
        jdbc.update("INSERT INTO order_events (order_id, event_type) VALUES (?::uuid, 'CANCELLED')", order.id());

        orderService.markPaid(order.id());

        String eventType = jdbc.queryForObject(
                "SELECT event_type FROM order_events WHERE order_id = ?::uuid ORDER BY occurred_at DESC LIMIT 1",
                String.class, order.id());
        assertThat(eventType).isEqualTo("CANCELLED");
        assertThat(paymentsForClosedOrder(order.id())).isEqualTo(1);
    }

    private int paymentsForClosedOrder(UUID orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'payment.received_for_closed_order' AND target_id = ?",
                Integer.class, "order " + orderId);
        return count == null ? 0 : count;
    }
}
