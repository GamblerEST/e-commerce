package ee.shop.privacy.service;

import ee.shop.catalog.repository.ProductRepository;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.web.dto.PersonalDataExport;
import ee.shop.order.model.Order;
import ee.shop.order.model.OrderEvent;
import ee.shop.order.repository.OrderEventRepository;
import ee.shop.order.repository.OrderLineRepository;
import ee.shop.order.repository.OrderRepository;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.review.repository.ReviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GDPR Article 15/20 (access and portability) and Article 17 (erasure).
 *
 * These existed on paper and nowhere else. anonymize_user() has been in the
 * schema since V1 and is described in DECISIONS.md §5 and four source files
 * as THE erasure mechanism — and no code path ever called it.
 * There was no way for a customer to obtain their data or to have it erased,
 * while the documentation read as though both were solved.
 */
@Service
public class PersonalDataService {

    private final UserRepository users;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final OrderEventRepository orderEvents;
    private final ReviewRepository reviews;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;

    public PersonalDataService(UserRepository users, OrderRepository orders,
                                OrderLineRepository orderLines, OrderEventRepository orderEvents,
                                ReviewRepository reviews, ProductRepository products,
                                JdbcTemplate jdbc) {
        this.users = users;
        this.orders = orders;
        this.orderLines = orderLines;
        this.orderEvents = orderEvents;
        this.reviews = reviews;
        this.products = products;
        this.jdbc = jdbc;
    }

    /**
     * Everything held about ONE person, scoped strictly to their own id.
     *
     * Read-only and assembled in memory rather than streamed: a single
     * customer's data is small, and the alternative — paging a download — buys
     * nothing at this scale.
     */
    @Transactional(readOnly = true)
    public PersonalDataExport export(UUID userId) {
        User user = users.findById(userId).orElseThrow();

        List<Order> userOrders = orders.findByUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, List<PersonalDataExport.Order.Line>> linesByOrder = new HashMap<>();
        if (!userOrders.isEmpty()) {
            // One query for every line, not one per order.
            orderLines.findByOrderIdIn(userOrders.stream().map(Order::getId).toList())
                    .forEach(l -> linesByOrder
                            .computeIfAbsent(l.getOrderId(), k -> new java.util.ArrayList<>())
                            .add(new PersonalDataExport.Order.Line(
                                    l.getProductName(), l.getQuantity(),
                                    l.getUnitPrice().toPlainString(),
                                    l.getLineTotal().toPlainString())));
        }

        List<PersonalDataExport.Order> exportedOrders = userOrders.stream()
                .map(o -> new PersonalDataExport.Order(
                        OrderResponse.referenceFor(o.getId()),
                        deriveStatus(o.getId()),
                        o.getCreatedAt(),
                        o.getCurrency(),
                        o.getTotal().toPlainString(),
                        // The SNAPSHOT on the order, which is the data actually
                        // held — not a live read of the current profile.
                        o.getBillingName(),
                        o.getBillingEmail(),
                        o.getBillingPhone(),
                        String.join(", ", java.util.stream.Stream.of(
                                o.getBillingAddressLine1(), o.getBillingAddressLine2(),
                                o.getBillingPostalCode(), o.getBillingCity(), o.getBillingCountry())
                                .filter(p -> p != null && !p.isBlank()).toList()),
                        linesByOrder.getOrDefault(o.getId(), List.of())))
                .toList();

        Map<UUID, String> productNames = new HashMap<>();
        var userReviews = reviews.findByUserIdOrderByCreatedAtDesc(userId);
        userReviews.forEach(r -> productNames.computeIfAbsent(r.getProductId(),
                id -> products.findById(id).map(p -> p.getName()).orElse("(product removed)")));

        List<PersonalDataExport.Review> exportedReviews = userReviews.stream()
                .map(r -> new PersonalDataExport.Review(
                        productNames.get(r.getProductId()), r.getRating(),
                        r.getTitle(), r.getBody(), r.getCreatedAt()))
                .toList();

        return new PersonalDataExport(
                Instant.now(),
                new PersonalDataExport.Account(
                        user.getEmail(), user.getDisplayName(), user.getRole().name(),
                        user.isEmailVerified(), user.is2faEnabled(),
                        user.getCreatedAt()),
                exportedOrders,
                exportedReviews);
    }

    /**
     * Erasure, Article 17. Delegates to the database function rather than
     * reimplementing it here.
     *
     * DECISIONS.md §5 puts it there deliberately: erasure must be atomic and
     * total, and a Java implementation would eventually be called from one code
     * path and forgotten in another. There is exactly one implementation, and
     * this is the one caller.
     *
     * What survives is intentional. Orders and invoices are retained seven
     * years under the Estonian Accounting Act — GDPR Art. 17(3)(b) exempts
     * exactly that — and they carry their own snapshot of the billing name and
     * address, which is what makes erasing the user row possible without
     * corrupting the books.
     */
    @Transactional
    public void erase(UUID userId) {
        // query(), not update(): anonymize_user() is a FUNCTION, so `SELECT`
        // returns a (single, null) row. update() rejects that with "a result
        // was returned when none was expected". The ResultSetExtractor
        // discards it — the function's effect is in what it wrote, not what it
        // returned.
        //
        // It RAISEs if the account does not exist or is already anonymised,
        // which surfaces as a DataAccessException rather than a silent no-op.
        jdbc.query("SELECT anonymize_user(?)", rs -> null, userId);
    }

    /** Status is folded from the append-only event stream; there is no status column. */
    private String deriveStatus(UUID orderId) {
        return orderEvents.findByOrderIdOrderByOccurredAt(orderId).stream()
                .max(Comparator.comparing(OrderEvent::getOccurredAt))
                .map(e -> e.getEventType().name())
                .orElse("CREATED");
    }
}
