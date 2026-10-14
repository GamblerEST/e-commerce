package ee.shop.order.service;

import ee.shop.cart.web.dto.AddCartItemRequest;
import ee.shop.common.audit.AuditService;
import ee.shop.common.mail.MailService;
import ee.shop.config.InvoiceProperties;
import ee.shop.order.model.Order;
import ee.shop.order.model.OrderEvent;
import ee.shop.order.model.OrderLine;
import ee.shop.order.repository.BillingDocumentRepository;
import ee.shop.order.repository.OrderEventRepository;
import ee.shop.order.repository.OrderLineRepository;
import ee.shop.order.repository.OrderRepository;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.payment.service.PaymentService;
import ee.shop.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What each order email actually says, and that the invoice rides along with the
 * payment confirmation without ever being able to break the payment itself.
 *
 * MailService is a mock so the subject and body a customer would receive can be
 * read directly; PaymentService is a mock because it talks to Stripe.
 */
@Tag("api")
@AutoConfigureMockMvc
class OrderEmailContentTest extends IntegrationTest {

    @MockitoBean MailService mail;
    @MockitoBean PaymentService payments;
    @MockitoSpyBean InvoiceService invoices;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orderService;
    @Autowired AdminOrderService adminOrderService;
    @Autowired OrderRepository orders;
    @Autowired OrderLineRepository orderLines;
    @Autowired OrderEventRepository orderEvents;
    @Autowired BillingDocumentRepository billingDocuments;
    @Autowired AuditService audit;

    private UUID insertProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency, vat_category) "
                + "VALUES (?, 'Wool blanket', ?, 30.00, 'EUR', 'STANDARD')", id, "email-" + id);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, 5, 0)", id);
        return id;
    }

    /** A CREATED order with one line, written straight to the repositories. */
    private Order createdOrder() {
        Order order = orders.save(new Order(null, "idem-" + UUID.randomUUID(), "Jaan Tamm", "jaan@test.ee",
                null, "Pikk tn 1", null, "Tallinn", "10123", "EE", "EUR",
                new BigDecimal("48.39"), new BigDecimal("11.61"), new BigDecimal("60.00"), null));
        orderLines.save(new OrderLine(order.getId(), insertProduct(), "Wool blanket", new BigDecimal("30.00"),
                new BigDecimal("0.2400"), 2, "EUR", new BigDecimal("60.00"), null));
        orderEvents.save(new OrderEvent(order.getId(), OrderEvent.Type.CREATED));
        return order;
    }

    private String currentStatus(UUID orderId) {
        return OrderEvent.deriveStatus(orderEvents.findByOrderIdOrderByOccurredAt(orderId));
    }

    // ── order received (checkout) ────────────────────────────────────────

    @Test
    void theCheckoutEmailSaysTheOrderWasReceived_notThatPaymentSucceeded() throws Exception {
        Cookie csrf = mvc.perform(get("/api/cart")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        UUID productId = insertProduct();
        MvcResult added = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isOk()).andReturn();
        Cookie cart = added.getResponse().getCookie("guest_cart_id");
        var billing = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", null, null, null, null);
        mvc.perform(post("/api/checkout")
                        .cookie(csrf, cart).header("X-XSRF-TOKEN", csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isCreated());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("jaan@test.ee"), subject.capture(), body.capture(), eq("order-confirmation"));
        assertThat(subject.getValue()).isEqualTo("Order received");
        assertThat(body.getValue()).contains("Wool blanket x1", "Total:", "does not mean your payment")
                .doesNotContain("successful", "Thank you for your purchase");
    }

    // ── payment received ─────────────────────────────────────────────────

    @Test
    void paymentReceivedCarriesTheInvoicePdfAndAnItemBreakdown() throws Exception {
        Order order = createdOrder();

        orderService.markPaid(order.getId());

        assertThat(currentStatus(order.getId())).isEqualTo("PAID");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
        verify(mail).sendWithAttachment(eq("jaan@test.ee"), eq("Payment received"), body.capture(),
                filename.capture(), pdf.capture(), eq("application/pdf"), eq("payment-received"));
        assertThat(body.getValue()).contains("Wool blanket x2", "Total: 60.00 EUR", "invoice is attached");
        assertThat(filename.getValue()).startsWith("invoice-ORD-").endsWith(".pdf");
        assertThat(new String(pdf.getValue(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        // ...and it was recorded: one document, issued by the system, plus a timeline event.
        assertThat(billingDocuments.findByOrderIdOrderByGeneratedAt(order.getId()))
                .singleElement().satisfies(d -> assertThat(d.getGeneratedByUserId()).isNull());
        assertThat(orderEvents.findByOrderIdOrderByOccurredAt(order.getId()))
                .extracting(OrderEvent::getEventType).contains(OrderEvent.Type.INVOICE_ISSUED);
    }

    /** The point of the graceful path: no PDF must never mean no payment confirmation. */
    @Test
    void ifTheInvoiceCannotBeIssuedThePaymentIsStillConfirmedByPlainEmail() throws Exception {
        Order order = createdOrder();
        doReturn(Optional.empty()).when(invoices).issueForPayment(any());

        orderService.markPaid(order.getId());

        assertThat(currentStatus(order.getId())).isEqualTo("PAID");
        verify(mail).send(eq("jaan@test.ee"), eq("Payment received"), anyString(), eq("payment-received"));
        verify(mail, never()).sendWithAttachment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRedeliveredPaymentMessageDoesNotIssueASecondInvoice() {
        Order order = createdOrder();

        orderService.markPaid(order.getId());
        orderService.markPaid(order.getId());

        assertThat(billingDocuments.findByOrderIdOrderByGeneratedAt(order.getId())).hasSize(1);
    }

    // ── payment failed ───────────────────────────────────────────────────

    @Test
    void paymentFailedKeepsTheReasonAndNowListsTheItems() {
        Order order = createdOrder();

        orderService.markPaymentFailed(order.getId(), "Your card has insufficient funds.");

        assertThat(currentStatus(order.getId())).isEqualTo("PAYMENT_FAILED");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("jaan@test.ee"), eq("Payment failed"), body.capture(), eq("payment-failed"));
        assertThat(body.getValue()).contains("insufficient funds", "try again", "Wool blanket x2", "Total: 60.00 EUR");
        verify(invoices, never()).issueForPayment(any());
    }

    // ── shipped and refunded (previously sent nothing at all) ────────────

    @Test
    void shippingNowEmailsTheCustomer() {
        Order order = createdOrder();
        orderService.markPaid(order.getId());

        adminOrderService.markShipped(order.getId());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("jaan@test.ee"), eq("Your order has shipped"), body.capture(), eq("order-shipped"));
        assertThat(body.getValue()).contains("on its way", "Wool blanket x2");
    }

    @Test
    void aRefundNowEmailsTheCustomer() {
        Order order = createdOrder();
        orderService.markPaid(order.getId());

        adminOrderService.refund(order.getId());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("jaan@test.ee"), eq("Your refund was processed"), body.capture(), eq("order-refunded"));
        assertThat(body.getValue()).contains("60.00 EUR", "refunded");
    }

    @Test
    void deliveryNowEmailsTheCustomer() {
        Order order = createdOrder();
        orderService.markPaid(order.getId());
        adminOrderService.markShipped(order.getId());

        adminOrderService.markDelivered(order.getId());

        assertThat(currentStatus(order.getId())).isEqualTo("DELIVERED");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("jaan@test.ee"), eq("Your order has been delivered"), body.capture(), eq("order-delivered"));
        assertThat(body.getValue()).contains("has been delivered", "Wool blanket x2");
    }

    /** A delivered order can still come back (a withdrawal, a fault), so it stays refundable. */
    @Test
    void aDeliveredOrderCanStillBeRefunded() {
        Order order = createdOrder();
        orderService.markPaid(order.getId());
        adminOrderService.markShipped(order.getId());
        adminOrderService.markDelivered(order.getId());

        adminOrderService.refund(order.getId());

        assertThat(currentStatus(order.getId())).isEqualTo("REFUNDED");
        verify(payments).refundForOrder(order.getId());
    }

    // ── InvoiceService on its own ────────────────────────────────────────

    /** A volume that cannot be written to: a "directory" that sits under a plain file. */
    @Test
    void issuingNeverThrowsWhenTheStorageCannotBeWritten_andTheFailureIsAudited() throws Exception {
        Order order = createdOrder();
        Path plainFile = Files.createTempFile("not-a-directory", ".tmp");
        InvoiceService broken = new InvoiceService(orders, orderLines, billingDocuments, orderEvents, audit,
                new InvoiceProperties(plainFile.resolve("invoices").toString(),
                        new InvoiceProperties.Seller("Shop", "1", "EE1", "Addr", "a@b.ee", "1")));

        Optional<?> result = broken.issueForPayment(order.getId());

        assertThat(result).isEmpty();
        assertThat(billingDocuments.findByOrderIdOrderByGeneratedAt(order.getId())).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'invoice.generation_failed' AND target_id = ?",
                Long.class, "order " + order.getId())).isEqualTo(1);
    }

    @Test
    void reissuingKeepsTheEarlierDocumentsExactlyAsTheyWere() throws Exception {
        Order order = createdOrder();
        orderService.markPaid(order.getId());
        var first = billingDocuments.findByOrderIdOrderByGeneratedAt(order.getId()).getFirst();
        byte[] firstBytes = invoices.readFile(first);

        invoices.reissue(order.getId(), null);

        List<?> all = billingDocuments.findByOrderIdOrderByGeneratedAt(order.getId());
        assertThat(all).hasSize(2);
        assertThat(invoices.readFile(first)).isEqualTo(firstBytes);
    }
}
