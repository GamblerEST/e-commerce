package ee.shop.review.web;

import ee.shop.common.security.EmailHasher;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.review.web.dto.ReviewListResponse;
import ee.shop.review.web.dto.ReviewRequest;
import ee.shop.review.web.dto.ReviewResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reviews. The rule under attack here is "only verified purchasers
 * may review" — so most of this file is the ways someone might try to review
 * something they didn't actually buy, not the happy path.
 *
 * Also covers the two DENORMALIZED numbers reviews are responsible for
 * (products.avg_rating/review_count and reviews.helpful_count). Those are
 * maintained by database triggers rather than Java (V15__reviews.sql), which
 * means they can only be verified by actually writing rows and reading the
 * numbers back — exactly what these tests do.
 */
@Tag("api")
@AutoConfigureMockMvc
class ReviewControllerTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    // ── fixtures ─────────────────────────────────────────────────────────

    private UUID insertProduct(String name, int onHand) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency) VALUES (?, ?, ?, '25.00', 'EUR')",
                id, name, "review-" + id);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    private record Account(String token, UUID id) {}

    private Account register() throws Exception {
        String email = "review-test-" + UUID.randomUUID() + "@test.ee";
        var req = new RegisterRequest(email, "correct-horse-battery", "Review Tester", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email_hash = ?", UUID.class, emailHasher.hash(email));
        return new Account(token, id);
    }

    private Cookie primeCsrf() throws Exception {
        Cookie c = mvc.perform(get("/api/cart")).andReturn().getResponse().getCookie(CSRF_COOKIE);
        assertThat(c).isNotNull();
        return c;
    }

    /** Buys the product for real (cart → checkout), leaving the order in CREATED. */
    private UUID buy(Account account, UUID productId) throws Exception {
        Cookie csrf = primeCsrf();
        MvcResult added = mvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + account.token())
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(added.getResponse().getStatus()).isEqualTo(200);

        var billing = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", null, null, null, null);
        MvcResult checkout = mvc.perform(post("/api/checkout")
                        .header("Authorization", "Bearer " + account.token())
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(checkout.getResponse().getContentAsString(), OrderResponse.class).id();
    }

    /** Appends an event directly — the same append-only pattern the payment consumer uses. */
    private void appendEvent(UUID orderId, String type) {
        jdbc.update("INSERT INTO order_events (order_id, event_type) VALUES (?, ?)", orderId, type);
    }

    private UUID buyAndPay(Account account, UUID productId) throws Exception {
        UUID orderId = buy(account, productId);
        appendEvent(orderId, "PAID");
        return orderId;
    }

    private ResultOf postReview(Account account, UUID productId, int rating, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/products/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewRequest((short) rating, "A title", body))))
                .andReturn();
        return new ResultOf(result);
    }

    private record ResultOf(MvcResult raw) {
        int status() { return raw.getResponse().getStatus(); }
    }

    // ── the verified-purchaser rule, attacked ────────────────────────────

    @Test
    void review_fromSomeoneWhoNeverBoughtIt_isForbidden() throws Exception {
        Account stranger = register();
        UUID productId = insertProduct("Never Bought Widget", 10);

        assertThat(postReview(stranger, productId, 5, "Fantastic, would recommend").status())
                .as("a stranger must not be able to review")
                .isEqualTo(403);
    }

    @Test
    void review_withAnUnpaidOrder_isForbidden() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Unpaid Widget", 10);
        buy(shopper, productId); // CREATED, never paid

        assertThat(postReview(shopper, productId, 5, "Reviewing something I never paid for").status())
                .as("an order that never reached PAID must not unlock reviewing")
                .isEqualTo(403);
    }

    @Test
    void review_withACancelledOrder_isForbidden() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Cancelled Widget", 10);
        UUID orderId = buy(shopper, productId);
        appendEvent(orderId, "CANCELLED");

        assertThat(postReview(shopper, productId, 1, "Cancelled it but still reviewing").status())
                .isEqualTo(403);
    }

    /** buy → review → refund would otherwise be a free review for anyone. */
    @Test
    void review_afterRefund_isForbidden() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Refunded Widget", 10);
        UUID orderId = buyAndPay(shopper, productId);
        appendEvent(orderId, "REFUNDED");

        assertThat(postReview(shopper, productId, 5, "Got my money back, still reviewing").status())
                .as("a refunded purchase must not keep granting review rights")
                .isEqualTo(403);
    }

    /**
     * Every payment is now followed by an INVOICE_ISSUED event. It is a fact about
     * the order, not its status, so it must not be mistaken for "the latest status"
     * and quietly take the review right away from everyone who has paid.
     */
    @Test
    void review_afterPaymentAndAnInvoice_isStillAllowed() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Invoiced Widget", 10);
        UUID orderId = buyAndPay(shopper, productId);
        appendEvent(orderId, "INVOICE_ISSUED");

        assertThat(postReview(shopper, productId, 5, "Paid, invoiced, and happy").status()).isEqualTo(201);
    }

    @Test
    void review_afterDelivery_isAllowed() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Delivered Widget", 10);
        UUID orderId = buyAndPay(shopper, productId);
        appendEvent(orderId, "SHIPPED");
        appendEvent(orderId, "DELIVERED");
        appendEvent(orderId, "INVOICE_ISSUED");

        assertThat(postReview(shopper, productId, 5, "It arrived and it is good").status()).isEqualTo(201);
    }

    @Test
    void review_afterRefundAndALaterInvoice_isStillForbidden() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Refunded And Invoiced Widget", 10);
        UUID orderId = buyAndPay(shopper, productId);
        appendEvent(orderId, "REFUNDED");
        appendEvent(orderId, "INVOICE_ISSUED");

        assertThat(postReview(shopper, productId, 5, "Trying to sneak a review in").status()).isEqualTo(403);
    }

    @Test
    void review_fromAVerifiedPurchaser_isCreated() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Bought Widget", 10);
        buyAndPay(shopper, productId);

        MvcResult result = mvc.perform(post("/api/products/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + shopper.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewRequest((short) 4, "Solid", "Does the job well."))))
                .andExpect(status().isCreated())
                .andReturn();

        ReviewResponse review = json.readValue(result.getResponse().getContentAsString(), ReviewResponse.class);
        assertThat(review.rating()).isEqualTo((short) 4);
        assertThat(review.authorName()).isEqualTo("Review Tester");
        assertThat(review.mine()).isTrue();
        assertThat(review.helpfulCount()).isZero();
    }

    @Test
    void secondReviewOfTheSameProduct_isConflict() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Double Review Widget", 10);
        buyAndPay(shopper, productId);

        assertThat(postReview(shopper, productId, 5, "First review").status()).isEqualTo(201);
        assertThat(postReview(shopper, productId, 1, "Changed my mind, second review").status())
                .as("one review per person per product")
                .isEqualTo(409);
    }

    @Test
    void ratingOutsideOneToFive_isRejected() throws Exception {
        Account shopper = register();
        UUID productId = insertProduct("Bad Rating Widget", 10);
        buyAndPay(shopper, productId);

        mvc.perform(post("/api/products/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + shopper.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewRequest((short) 9, null, "Nine stars"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").value("must be between 1 and 5"));
    }

    // ── the trigger-maintained denormalized rating ───────────────────────

    @Test
    void productRating_isMaintainedByTheDatabase_asReviewsLand() throws Exception {
        UUID productId = insertProduct("Rated Widget", 10);

        Account first = register();
        buyAndPay(first, productId);
        assertThat(postReview(first, productId, 5, "Excellent").status()).isEqualTo(201);

        assertThat(reviewCountOf(productId)).isEqualTo(1);
        assertThat(avgRatingOf(productId)).isEqualByComparingTo("5.00");

        Account second = register();
        buyAndPay(second, productId);
        assertThat(postReview(second, productId, 2, "Not for me").status()).isEqualTo(201);

        assertThat(reviewCountOf(productId)).isEqualTo(2);
        assertThat(avgRatingOf(productId)).as("(5 + 2) / 2").isEqualByComparingTo("3.50");
    }

    @Test
    void hidingAReview_dropsItFromBothTheRatingAndThePublicList() throws Exception {
        UUID productId = insertProduct("Moderated Widget", 10);
        Account keeper = register();
        Account offender = register();
        buyAndPay(keeper, productId);
        buyAndPay(offender, productId);

        assertThat(postReview(keeper, productId, 4, "Genuine review").status()).isEqualTo(201);
        MvcResult bad = mvc.perform(post("/api/products/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + offender.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewRequest((short) 1, null, "Spam spam spam"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID badId = json.readValue(bad.getResponse().getContentAsString(), ReviewResponse.class).id();

        assertThat(avgRatingOf(productId)).as("(4 + 1) / 2 while both are visible").isEqualByComparingTo("2.50");

        jdbc.update("UPDATE reviews SET status = 'HIDDEN' WHERE id = ?", badId);

        assertThat(reviewCountOf(productId)).as("hidden reviews stop counting").isEqualTo(1);
        assertThat(avgRatingOf(productId)).isEqualByComparingTo("4.00");
        assertThat(publicReviews(productId).items()).hasSize(1);
    }

    // ── helpful voting ───────────────────────────────────────────────────

    @Test
    void markingHelpful_isIdempotent_soADoubleClickCannotInflateIt() throws Exception {
        UUID productId = insertProduct("Helpful Widget", 10);
        Account author = register();
        Account reader = register();
        buyAndPay(author, productId);
        UUID reviewId = createdReviewId(author, productId);

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/reviews/" + reviewId + "/helpful")
                            .header("Authorization", "Bearer " + reader.token()))
                    .andExpect(status().isNoContent());
        }

        assertThat(helpfulCountOf(reviewId)).as("three clicks, one vote").isEqualTo(1);

        mvc.perform(delete("/api/reviews/" + reviewId + "/helpful")
                        .header("Authorization", "Bearer " + reader.token()))
                .andExpect(status().isNoContent());
        assertThat(helpfulCountOf(reviewId)).isZero();
    }

    @Test
    void markingYourOwnReviewHelpful_isForbidden() throws Exception {
        UUID productId = insertProduct("Self Vote Widget", 10);
        Account author = register();
        buyAndPay(author, productId);
        UUID reviewId = createdReviewId(author, productId);

        mvc.perform(post("/api/reviews/" + reviewId + "/helpful")
                        .header("Authorization", "Bearer " + author.token()))
                .andExpect(status().isForbidden());
        assertThat(helpfulCountOf(reviewId)).isZero();
    }

    // ── ownership + moderation authorization ─────────────────────────────

    @Test
    void editingSomeoneElsesReview_is404_notForbidden() throws Exception {
        UUID productId = insertProduct("Ownership Widget", 10);
        Account author = register();
        Account meddler = register();
        buyAndPay(author, productId);
        UUID reviewId = createdReviewId(author, productId);

        // 404 rather than 403 — don't confirm someone else's review exists,
        // same posture as OrderController.
        mvc.perform(patch("/api/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + meddler.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewRequest((short) 1, null, "Rewritten by someone else"))))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + meddler.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void moderationEndpoints_rejectPlainCustomers() throws Exception {
        Account customer = register();

        mvc.perform(get("/api/admin/reviews").header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isForbidden());
    }

    /**
     * @StaffOnly requires the second factor as well as the role — a
     * privileged account that never enabled 2FA can log in, but cannot act.
     */
    @Test
    void moderationEndpoints_rejectStaffWithoutTwoFactor() throws Exception {
        Account agent = register();
        jdbc.update("UPDATE users SET role = 'SUPPORT' WHERE id = ?", agent.id());

        mvc.perform(get("/api/admin/reviews").header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
    }

    /** Moderation is deliberately SUPPORT-level work, not admin-only — see StaffOnly. */
    @Test
    void supportWithTwoFactor_canHideAReview_andItLeavesThePublicList() throws Exception {
        UUID productId = insertProduct("Support Moderated Widget", 10);
        Account author = register();
        buyAndPay(author, productId);
        UUID reviewId = createdReviewId(author, productId);

        Account agent = register();
        enableStaff(agent, "SUPPORT");

        mvc.perform(patch("/api/admin/reviews/" + reviewId)
                        .param("status", "HIDDEN")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"));

        assertThat(publicReviews(productId).items()).isEmpty();
        assertThat(reviewCountOf(productId)).isZero();
    }

    /**
     * The other path into the same trigger. AVG() over zero rows is NULL and
     * avg_rating is NOT NULL, so removing a product's LAST review is the
     * case that breaks if the aggregate isn't COALESCEd — and a shop with
     * exactly one review is the common case, not an exotic one.
     */
    @Test
    void deletingTheOnlyReview_resetsTheProductRatingToZero() throws Exception {
        UUID productId = insertProduct("Last Review Widget", 10);
        Account author = register();
        buyAndPay(author, productId);
        UUID reviewId = createdReviewId(author, productId);
        assertThat(reviewCountOf(productId)).isEqualTo(1);

        mvc.perform(delete("/api/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + author.token()))
                .andExpect(status().isNoContent());

        assertThat(reviewCountOf(productId)).isZero();
        assertThat(avgRatingOf(productId)).isEqualByComparingTo("0.00");
    }

    private void enableStaff(Account account, String role) {
        jdbc.update("UPDATE users SET role = ?, totp_secret_enc = 'test-secret', totp_enabled_at = now() "
                + "WHERE id = ?", role, account.id());
    }

    @Test
    void publicReviewList_isReadableAnonymously() throws Exception {
        UUID productId = insertProduct("Anonymous Read Widget", 10);
        Account author = register();
        buyAndPay(author, productId);
        createdReviewId(author, productId);

        MvcResult result = mvc.perform(get("/api/products/" + productId + "/reviews"))
                .andExpect(status().isOk())
                .andReturn();

        ReviewListResponse list = json.readValue(result.getResponse().getContentAsString(), ReviewListResponse.class);
        assertThat(list.items()).hasSize(1);
        // An anonymous reader is nobody's author and has voted on nothing.
        assertThat(list.items().get(0).mine()).isFalse();
        assertThat(list.items().get(0).votedByMe()).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private UUID createdReviewId(Account account, UUID productId) throws Exception {
        MvcResult result = mvc.perform(post("/api/products/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewRequest((short) 5, "Title", "Body text here."))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), ReviewResponse.class).id();
    }

    private ReviewListResponse publicReviews(UUID productId) throws Exception {
        MvcResult result = mvc.perform(get("/api/products/" + productId + "/reviews"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), ReviewListResponse.class);
    }

    private Integer reviewCountOf(UUID productId) {
        return jdbc.queryForObject("SELECT review_count FROM products WHERE id = ?", Integer.class, productId);
    }

    private BigDecimal avgRatingOf(UUID productId) {
        return jdbc.queryForObject("SELECT avg_rating FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    /**
     * Created in the order A, B, C, so newest-first would read C, B, A. Votes give
     * B two, C one and A none, so helpful-first must read B, C, A. A test that
     * only checked one order could pass by accident.
     */
    @Test
    void sortingByHelpful_putsTheMostVotedReviewFirst() throws Exception {
        UUID productId = insertProduct("Sorted Widget", 10);
        Account authorA = register();
        Account authorB = register();
        Account authorC = register();
        Account voterOne = register();
        Account voterTwo = register();
        buyAndPay(authorA, productId);
        buyAndPay(authorB, productId);
        buyAndPay(authorC, productId);
        UUID reviewA = createdReviewId(authorA, productId);
        UUID reviewB = createdReviewId(authorB, productId);
        UUID reviewC = createdReviewId(authorC, productId);

        vote(voterOne, reviewB);
        vote(voterTwo, reviewB);
        vote(voterOne, reviewC);

        MvcResult result = mvc.perform(get("/api/products/" + productId + "/reviews").param("sort", "HELPFUL"))
                .andExpect(status().isOk())
                .andReturn();
        ReviewListResponse list = json.readValue(result.getResponse().getContentAsString(), ReviewListResponse.class);

        assertThat(list.items()).extracting(ReviewResponse::id)
                .containsExactly(reviewB, reviewC, reviewA);
        assertThat(list.items()).extracting(ReviewResponse::helpfulCount)
                .containsExactly(2, 1, 0);
    }

    private void vote(Account voter, UUID reviewId) throws Exception {
        mvc.perform(post("/api/reviews/" + reviewId + "/helpful")
                        .header("Authorization", "Bearer " + voter.token()))
                .andExpect(status().isNoContent());
    }

    private Integer helpfulCountOf(UUID reviewId) {
        return jdbc.queryForObject("SELECT helpful_count FROM reviews WHERE id = ?", Integer.class, reviewId);
    }
}
