package ee.shop.payment.banklink;

import ee.shop.cart.web.dto.AddCartItemRequest;
import ee.shop.common.error.AppException;
import ee.shop.order.service.AdminOrderService;
import ee.shop.order.service.BankLinkRefundReconciler;
import ee.shop.order.service.OrderService;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.support.IntegrationTest;
import ee.shop.support.MontonioStub;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bank-link payments end to end against a local Montonio: checkout, the signed
 * result coming back by notification and by the customer's return, and refunds
 * through to their outcome.
 *
 * Most of these attack it. A result is money arriving, so the tests that matter
 * most prove that a forged, altered or misdirected result changes nothing, and
 * that refunds requested or settled concurrently pay out once.
 */
@Tag("api")
@Tag("flow")
@Tag("security")
@AutoConfigureMockMvc
class BankLinkPaymentFlowTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String GUEST_CART_COOKIE = "guest_cart_id";
    private static final String BANK = "HABAEE2X";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orderService;
    @Autowired AdminOrderService adminOrders;
    @Autowired BankLinkPaymentService bankLinks;
    @Autowired BankLinkRefundReconciler refundReconciler;

    private record PlacedOrder(OrderResponse order, UUID productId, String montonioOrderUuid) {}

    // ── offering the choice ──────────────────────────────────────────────

    @Test
    void checkoutOffersEstonianEuroBanks_inMontoniosOrder_withoutThirdPartyLogos() throws Exception {
        mvc.perform(get("/api/payments/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankLink").value(true))
                .andExpect(jsonPath("$.banks.length()").value(2))
                .andExpect(jsonPath("$.banks[0].code").value("HABAEE2X"))
                .andExpect(jsonPath("$.banks[1].code").value("LHVBEE22"))
                .andExpect(jsonPath("$.banks[0].logoUrl").doesNotExist());
    }

    // ── paying ───────────────────────────────────────────────────────────

    @Test
    void bankLinkCheckout_sendsTheCustomerToTheirBank_andMontonioNoPersonalDetails() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();
        OrderResponse order = placed.order();

        assertThat(order.paymentRedirectUrl()).isEqualTo("https://pay.example/" + placed.montonioOrderUuid());
        assertThat(order.paymentClientSecret()).isNull();

        JsonNode sent = sentToMontonio(order.id());
        assertThat(new BigDecimal(MontonioJwt.text(sent.path("grandTotal")))).isEqualByComparingTo(order.total());
        assertThat(MontonioJwt.text(sent.path("currency"))).isEqualTo("EUR");
        JsonNode options = sent.path("payment").path("methodOptions");
        assertThat(MontonioJwt.text(options.path("preferredProvider"))).isEqualTo(BANK);
        assertThat(MontonioJwt.text(options.path("paymentDescription"))).isEqualTo(order.reference());
        assertThat(MontonioJwt.text(sent.path("returnUrl"))).endsWith("/checkout/bank-link/return");
        assertThat(MontonioJwt.text(sent.path("notificationUrl"))).endsWith("/api/payments/bank-link/notification");
        assertThat(sent.path("expiresIn").asLong())
                .as("the payment link lasts as long as the stock is reserved, in minutes")
                .isEqualTo(15);
        assertThat(sent.toString()).doesNotContain("Jaan Tamm", "jaan@test.ee", "5555 5555", "Pikk tn 1");
    }

    @Test
    void aBankMontonioDoesNotOfferForEuros_isRefusedBeforeAnyStockIsHeld() throws Exception {
        Cookie csrf = csrf();
        UUID productId = insertProduct(5);
        Cookie cart = guestCartWith(csrf, productId);

        checkout(csrf, cart, UUID.randomUUID().toString(), bankLinkCheckout("PLNONLY1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bankCode").exists());
        checkout(csrf, cart, UUID.randomUUID().toString(), bankLinkCheckout(null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bankCode").exists());

        assertThat(count("SELECT reserved FROM inventory WHERE product_id = ?", productId)).isZero();
        assertThat(count("SELECT count(*) FROM order_lines WHERE product_id = ?", productId)).isZero();
    }

    @Test
    void aRetriedCheckout_getsTheSamePaymentPage_notASecondPayment() throws Exception {
        Cookie csrf = csrf();
        Cookie cart = guestCartWith(csrf, insertProduct(5));
        String key = UUID.randomUUID().toString();

        OrderResponse first = read(checkout(csrf, cart, key, bankLinkCheckout(BANK)).andExpect(status().isCreated()));
        OrderResponse retry = read(checkout(csrf, cart, key, bankLinkCheckout(BANK)).andExpect(status().isCreated()));

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.paymentRedirectUrl()).isEqualTo(first.paymentRedirectUrl());
        sentToMontonio(first.id());
    }

    @Test
    void aRetryThatSwitchesToCard_isRefused_soOneOrderIsNeverPaidTwoWays() throws Exception {
        Cookie csrf = csrf();
        Cookie cart = guestCartWith(csrf, insertProduct(5));
        String key = UUID.randomUUID().toString();
        checkout(csrf, cart, key, bankLinkCheckout(BANK)).andExpect(status().isCreated());

        CheckoutRequest card = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555", "Pikk tn 1", null,
                "Tallinn", "10123", "EE", null, null, CheckoutRequest.PaymentMethod.CARD, null);
        checkout(csrf, cart, key, card).andExpect(status().isConflict());
    }

    @Test
    void aPaidResult_marksTheOrderPaidOnce_howeverOftenItArrives() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();
        UUID orderId = placed.order().id();
        String paid = resultFor(placed, "PAID");

        notification(paid).andExpect(status().isOk());
        notification(paid).andExpect(status().isOk());

        assertThat(awaitStatus(orderId, "PAID")).isEqualTo("PAID");
        assertThat(count("SELECT count(*) FROM order_events WHERE order_id = ? AND event_type = 'PAID'", orderId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM processed_bank_link_results WHERE montonio_order_uuid = ?",
                placed.montonioOrderUuid())).isEqualTo(1);
        assertThat(count("SELECT on_hand FROM inventory WHERE product_id = ?", placed.productId())).isEqualTo(4);
        assertThat(count("SELECT reserved FROM inventory WHERE product_id = ?", placed.productId())).isZero();
    }

    @Test
    void anAbandonedPayment_failsTheOrder_andReleasesItsStock() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();

        notification(resultFor(placed, "ABANDONED")).andExpect(status().isOk());

        assertThat(awaitStatus(placed.order().id(), "PAYMENT_FAILED")).isEqualTo("PAYMENT_FAILED");
        assertThat(count("SELECT reserved FROM inventory WHERE product_id = ?", placed.productId())).isZero();
        assertThat(count("SELECT on_hand FROM inventory WHERE product_id = ?", placed.productId())).isEqualTo(5);
    }

    @Test
    void aPendingResult_settlesNothingYet() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();

        notification(resultFor(placed, "PENDING")).andExpect(status().isOk());

        assertNothingSettled(placed);
    }

    // ── forged, altered and misdirected results ──────────────────────────

    @Test
    void aResultSignedWithAnyOtherKey_isRejected_andTheOrderStaysUnpaid() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();
        MontonioJwt forger = new MontonioJwt(json, "not-the-store-secret", Clock.systemUTC());

        notification(forger.sign(paidClaims(placed, MontonioStub.ACCESS_KEY))).andExpect(status().isBadRequest());

        assertNothingSettled(placed);
    }

    @Test
    void aResultNamingAnotherStoresAccessKey_isRejected() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();
        MontonioJwt signer = new MontonioJwt(json, MontonioStub.SECRET_KEY, Clock.systemUTC());

        notification(signer.sign(paidClaims(placed, "another-store"))).andExpect(status().isBadRequest());

        assertNothingSettled(placed);
    }

    @Test
    void aGenuineResultForADifferentAmount_isRejected_andRecordedForStaff() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();
        String underpaid = MONTONIO.resultToken(placed.montonioOrderUuid(), placed.order().id().toString(),
                "PAID", placed.order().total().subtract(new BigDecimal("0.01")), "EUR");

        notification(underpaid).andExpect(status().isBadRequest());

        assertNothingSettled(placed);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'payment.bank_link_result_mismatch' "
                + "AND target_id = ?", "order " + placed.order().id())).isEqualTo(1);
    }

    @Test
    void aResultCannotBeCrossedOntoAnotherOrder() throws Exception {
        PlacedOrder one = placeBankLinkOrder();
        PlacedOrder other = placeBankLinkOrder();
        // Montonio's id for one order, this shop's reference for the other.
        String crossed = MONTONIO.resultToken(other.montonioOrderUuid(), one.order().id().toString(),
                "PAID", other.order().total(), "EUR");

        notification(crossed).andExpect(status().isBadRequest());

        assertNothingSettled(one);
        assertNothingSettled(other);
    }

    @Test
    void aResultForAPaymentThisShopNeverStarted_isRejected() throws Exception {
        String unknown = MONTONIO.resultToken(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "PAID", new BigDecimal("30.00"), "EUR");

        notification(unknown).andExpect(status().isBadRequest());
    }

    // ── the customer comes back ──────────────────────────────────────────

    @Test
    void returningFromTheBank_confirmsThePurchase_withoutAnyPersonalDetails() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();

        MvcResult result = mvc.perform(post("/api/payments/bank-link/return")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("orderToken", resultFor(placed, "PAID")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(placed.order().reference()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.lines[0].productName").value("Bank Link Widget"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Jaan Tamm", "jaan@test.ee", "5555 5555", "Pikk tn 1",
                        placed.order().id().toString());
        assertThat(awaitStatus(placed.order().id(), "PAID"))
                .as("the return settles the order just as the notification would")
                .isEqualTo("PAID");
    }

    @Test
    void moneyArrivingForAnOrderAlreadyCancelled_isLeftInTheAuditLogForStaff() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();
        UUID orderId = placed.order().id();
        // Cancelled while the customer was still at their bank.
        jdbc.update("INSERT INTO order_events (order_id, event_type) VALUES (?, 'CANCELLED')", orderId);

        notification(resultFor(placed, "PAID")).andExpect(status().isOk());

        assertThat(awaitAudit("payment.received_for_closed_order", orderId)).isTrue();
        assertThat(latestStatus(orderId)).isEqualTo("CANCELLED");
    }

    // ── refunds ──────────────────────────────────────────────────────────

    @Test
    void aRefund_isRequestedFirst_andRefundedOnlyOnceMontonioHasPaidItOut() throws Exception {
        PlacedOrder placed = paidBankLinkOrder();
        UUID orderId = placed.order().id();

        assertThat(adminOrders.refund(orderId).status()).isEqualTo("REFUND_REQUESTED");

        List<JsonNode> sent = refundRequestsFor(placed);
        assertThat(sent).hasSize(1);
        assertThat(MontonioJwt.text(sent.getFirst().path("idempotencyKey"))).isEqualTo("refund-" + orderId + "-1");
        assertThat(new BigDecimal(MontonioJwt.text(sent.getFirst().path("amount"))))
                .isEqualByComparingTo(placed.order().total());

        refundReconciler.reconcile();
        assertThat(latestStatus(orderId)).as("still pending at Montonio").isEqualTo("REFUND_REQUESTED");

        MONTONIO.settleLatestRefund(placed.montonioOrderUuid(), "SUCCESSFUL");
        refundReconciler.reconcile();
        refundReconciler.reconcile();

        assertThat(latestStatus(orderId)).isEqualTo("REFUNDED");
        assertThat(count("SELECT count(*) FROM order_events WHERE order_id = ? AND event_type = 'REFUNDED'", orderId))
                .isEqualTo(1);
    }

    @Test
    void aSecondRefund_isRefusedWhileTheFirstIsStillPending() throws Exception {
        PlacedOrder placed = paidBankLinkOrder();
        adminOrders.refund(placed.order().id());

        assertThatThrownBy(() -> adminOrders.refund(placed.order().id()))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("REFUND_REQUESTED");
        assertThat(refundRequestsFor(placed)).hasSize(1);
    }

    @Test
    void aRejectedRefund_putsTheOrderBack_andTheNextAttemptUsesANewKey() throws Exception {
        PlacedOrder placed = paidBankLinkOrder();
        UUID orderId = placed.order().id();
        adminOrders.refund(orderId);

        MONTONIO.settleLatestRefund(placed.montonioOrderUuid(), "REJECTED");
        refundReconciler.reconcile();

        assertThat(latestStatus(orderId)).as("the money never moved").isEqualTo("PAID");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'payment.bank_link_refund_rejected' "
                + "AND target_id = ?", "order " + orderId)).isEqualTo(1);

        assertThat(adminOrders.refund(orderId).status()).isEqualTo("REFUND_REQUESTED");
        assertThat(refundRequestsFor(placed))
                .extracting(r -> MontonioJwt.text(r.path("idempotencyKey")))
                .containsExactly("refund-" + orderId + "-1", "refund-" + orderId + "-2");
    }

    @Test
    void aRefundMontonioRefuses_leavesTheOrderPaid_andEndsThatAttempt() throws Exception {
        PlacedOrder placed = paidBankLinkOrder();
        UUID orderId = placed.order().id();
        MONTONIO.refuseNextRefund("Refunds are not enabled for this store");

        assertThatThrownBy(() -> adminOrders.refund(orderId))
                .isInstanceOf(MontonioClient.RefusedException.class)
                .hasMessageContaining("Refunds are not enabled for this store");
        assertThat(latestStatus(orderId)).isEqualTo("PAID");

        assertThat(adminOrders.refund(orderId).status()).isEqualTo("REFUND_REQUESTED");
        assertThat(MontonioJwt.text(refundRequestsFor(placed).getLast().path("idempotencyKey")))
                .isEqualTo("refund-" + orderId + "-2");
    }

    /**
     * The refund reached Montonio but its answer was lost. Retrying under a new key
     * would pay the customer back twice.
     */
    @Test
    void aRefundWhoseAnswerWasLost_isRetriedWithTheSameKey_andPaidOutOnce() throws Exception {
        PlacedOrder placed = paidBankLinkOrder();
        UUID orderId = placed.order().id();
        MONTONIO.loseNextRefundResponse();

        assertThatThrownBy(() -> adminOrders.refund(orderId)).isInstanceOf(AppException.class);
        assertThat(latestStatus(orderId)).isEqualTo("PAID");

        assertThat(adminOrders.refund(orderId).status()).isEqualTo("REFUND_REQUESTED");
        assertThat(refundRequestsFor(placed))
                .extracting(r -> MontonioJwt.text(r.path("idempotencyKey")))
                .containsOnly("refund-" + orderId + "-1");
        assertThat(MONTONIO.refundsFor(placed.montonioOrderUuid())).hasSize(1);

        MONTONIO.settleLatestRefund(placed.montonioOrderUuid(), "SUCCESSFUL");
        refundReconciler.reconcile();
        assertThat(latestStatus(orderId)).isEqualTo("REFUNDED");
    }

    // ── concurrency ──────────────────────────────────────────────────────

    @Test
    void tenConcurrentRefundRequests_shareOneAttempt_andMontonioCreatesOneRefund() throws Exception {
        PlacedOrder placed = paidBankLinkOrder();
        UUID orderId = placed.order().id();

        List<Throwable> failures = runConcurrently(10, () -> bankLinks.requestRefund(orderId));

        assertThat(failures).isEmpty();
        assertThat(count("SELECT count(*) FROM bank_link_refunds WHERE order_id = ?", orderId)).isEqualTo(1);
        assertThat(refundRequestsFor(placed))
                .hasSize(10)
                .extracting(r -> MontonioJwt.text(r.path("idempotencyKey")))
                .containsOnly("refund-" + orderId + "-1");
        assertThat(MONTONIO.refundsFor(placed.montonioOrderUuid())).hasSize(1);
    }

    @Test
    void tenConcurrentReconciliations_recordTheRefundExactlyOnce() throws Exception {
        PlacedOrder placed = paidBankLinkOrder();
        UUID orderId = placed.order().id();
        adminOrders.refund(orderId);
        MONTONIO.settleLatestRefund(placed.montonioOrderUuid(), "SUCCESSFUL");

        List<Throwable> failures = runConcurrently(10, refundReconciler::reconcile);

        assertThat(failures).isEmpty();
        assertThat(count("SELECT count(*) FROM order_events WHERE order_id = ? AND event_type = 'REFUNDED'", orderId))
                .isEqualTo(1);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private UUID insertProduct(int onHand) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency, vat_category) "
                + "VALUES (?, 'Bank Link Widget', ?, 30.00, 'EUR', 'STANDARD')", id, "banklink-" + id);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    private Cookie csrf() throws Exception {
        Cookie csrf = mvc.perform(get("/api/cart")).andReturn().getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private Cookie guestCartWith(Cookie csrf, UUID productId) throws Exception {
        MvcResult added = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cart = added.getResponse().getCookie(GUEST_CART_COOKIE);
        assertThat(cart).isNotNull();
        return cart;
    }

    private static CheckoutRequest bankLinkCheckout(String bankCode) {
        return new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555", "Pikk tn 1", null,
                "Tallinn", "10123", "EE", null, null, CheckoutRequest.PaymentMethod.BANK_LINK, bankCode);
    }

    private ResultActions checkout(Cookie csrf, Cookie cart, String idempotencyKey, CheckoutRequest req)
            throws Exception {
        return mvc.perform(post("/api/checkout")
                .cookie(csrf, cart).header(CSRF_HEADER, csrf.getValue())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));
    }

    private OrderResponse read(ResultActions actions) throws Exception {
        return json.readValue(actions.andReturn().getResponse().getContentAsString(), OrderResponse.class);
    }

    private PlacedOrder placeBankLinkOrder() throws Exception {
        Cookie csrf = csrf();
        UUID productId = insertProduct(5);
        OrderResponse order = read(checkout(csrf, guestCartWith(csrf, productId), UUID.randomUUID().toString(),
                bankLinkCheckout(BANK)).andExpect(status().isCreated()));
        String montonioOrderUuid = jdbc.queryForObject(
                "SELECT montonio_order_uuid FROM bank_link_payments WHERE order_id = ?", String.class, order.id());
        return new PlacedOrder(order, productId, montonioOrderUuid);
    }

    /** Paid directly rather than through the queue, so refund tests start from a settled order. */
    private PlacedOrder paidBankLinkOrder() throws Exception {
        PlacedOrder placed = placeBankLinkOrder();
        orderService.markPaid(placed.order().id());
        return placed;
    }

    private JsonNode sentToMontonio(UUID orderId) {
        List<JsonNode> matching = MONTONIO.createdOrders().stream()
                .filter(o -> orderId.toString().equals(MontonioJwt.text(o.path("merchantReference"))))
                .toList();
        assertThat(matching).as("orders Montonio was asked to create for this one").hasSize(1);
        return matching.getFirst();
    }

    private List<JsonNode> refundRequestsFor(PlacedOrder placed) {
        return MONTONIO.refundRequests().stream()
                .filter(r -> placed.montonioOrderUuid().equals(MontonioJwt.text(r.path("orderUuid"))))
                .toList();
    }

    private String resultFor(PlacedOrder placed, String paymentStatus) {
        return MONTONIO.resultToken(placed.montonioOrderUuid(), placed.order().id().toString(), paymentStatus,
                placed.order().total(), placed.order().currency());
    }

    private static Map<String, Object> paidClaims(PlacedOrder placed, String accessKey) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("uuid", placed.montonioOrderUuid());
        claims.put("accessKey", accessKey);
        claims.put("merchantReference", placed.order().id().toString());
        claims.put("paymentStatus", "PAID");
        claims.put("grandTotal", placed.order().total());
        claims.put("currency", placed.order().currency());
        claims.put("iat", now);
        claims.put("exp", now + 600);
        return claims;
    }

    private ResultActions notification(String orderToken) throws Exception {
        return mvc.perform(post("/api/payments/bank-link/notification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("orderToken", orderToken))));
    }

    private void assertNothingSettled(PlacedOrder placed) {
        assertThat(count("SELECT count(*) FROM processed_bank_link_results WHERE montonio_order_uuid = ?",
                placed.montonioOrderUuid())).isZero();
        assertThat(latestStatus(placed.order().id())).isEqualTo("CREATED");
    }

    /**
     * The most recent STATUS event. INVOICE_ISSUED is appended right after every
     * payment but is only a fact about a document, never the order's status.
     */
    private String latestStatus(UUID orderId) {
        List<String> latest = jdbc.queryForList(
                "SELECT event_type FROM order_events WHERE order_id = ? AND event_type <> 'INVOICE_ISSUED' "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class, orderId);
        return latest.isEmpty() ? "CREATED" : latest.getFirst();
    }

    /** Results travel through the payment queue, so the order changes a moment after the request. */
    private String awaitStatus(UUID orderId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String current = latestStatus(orderId);
        while (!expected.equals(current) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            current = latestStatus(orderId);
        }
        return current;
    }

    private boolean awaitAudit(String action, UUID orderId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
                    action, "order " + orderId) > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static List<Throwable> runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }
        start.countDown();
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            } catch (TimeoutException e) {
                failures.add(e);
            }
        }
        pool.shutdownNow();
        return failures;
    }
}
