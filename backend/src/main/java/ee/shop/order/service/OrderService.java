package ee.shop.order.service;

import ee.shop.cart.service.GuestCartItem;
import ee.shop.catalog.model.Product;
import ee.shop.catalog.repository.ProductRepository;
import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.common.mail.MailService;
import ee.shop.delivery.model.DeliveryOption;
import ee.shop.delivery.repository.DeliveryOptionRepository;
import ee.shop.config.TaxProperties;
import ee.shop.inventory.service.InventoryService;
import ee.shop.order.model.BillingDocument;
import ee.shop.order.model.DeliverySnapshot;
import ee.shop.order.model.Order;
import ee.shop.order.model.OrderEvent;
import ee.shop.order.model.OrderLine;
import ee.shop.order.repository.OrderEventRepository;
import ee.shop.order.repository.OrderLineRepository;
import ee.shop.order.repository.OrderRepository;
import ee.shop.order.web.dto.CheckoutPrefillResponse;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.DeliveryDetails;
import ee.shop.order.web.dto.OrderEventResponse;
import ee.shop.order.web.dto.OrderLineResponse;
import ee.shop.order.web.dto.OrderListResponse;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.order.web.dto.OrderSummaryResponse;
import ee.shop.payment.service.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a cart into an immutable order. Line items
 * are NEVER taken from the request — only from the caller's own
 * server-side cart, re-priced from the LIVE product row at this exact
 * moment (never trusting whatever the cart last computed) and reserved in
 * inventory before any order data is written.
 *
 * Payment is a separate step — every order created here
 * lands in CREATED status (see OrderEvent.Type), inventory held via
 * reservations, awaiting a payment outcome that later stages will append.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final OrderEventRepository orderEvents;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final TaxProperties tax;
    private final MailService mail;
    private final PaymentService payments;
    private final DeliveryOptionRepository deliveryOptions;
    private final AuditService audit;
    private final InvoiceService invoices;

    public OrderService(OrderRepository orders, OrderLineRepository orderLines,
                         OrderEventRepository orderEvents, ProductRepository products,
                         InventoryService inventory, TaxProperties tax, MailService mail,
                         PaymentService payments, DeliveryOptionRepository deliveryOptions,
                         AuditService audit, InvoiceService invoices) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.orderEvents = orderEvents;
        this.products = products;
        this.inventory = inventory;
        this.tax = tax;
        this.mail = mail;
        this.payments = payments;
        this.deliveryOptions = deliveryOptions;
        this.audit = audit;
        this.invoices = invoices;
    }

    public Optional<OrderResponse> findByIdempotencyKey(String idempotencyKey) {
        return orders.findByIdempotencyKey(idempotencyKey).map(this::toResponse);
    }

    public Optional<OrderResponse> findById(UUID id) {
        return orders.findById(id).map(this::toResponse);
    }

    /**
     * Reserves inventory and prices lines BEFORE the Order row is even
     * constructed, so if pricing/reservation fails partway through
     * (out of stock, product removed), throwing here rolls back the WHOLE
     * transaction — including any reservations already claimed for earlier
     * lines in this same attempt. No manual compensation/rollback logic is
     * needed; that's what @Transactional is for.
     *
     * The idempotency_key UNIQUE constraint (V12__orders.sql) is the real
     * guard against a double-submit: if orders.save() below loses a race to
     * a concurrent identical request, it throws
     * DataIntegrityViolationException, which propagates out of this method
     * (rolling back everything, reservations included) for the caller
     * (CheckoutController) to catch and resolve by re-fetching the winner's
     * order via findByIdempotencyKey — see that controller for why this
     * method deliberately does NOT catch it itself.
     */
    @Transactional
    public OrderResponse createOrder(UUID userId, String idempotencyKey, CheckoutRequest billing,
                                      List<GuestCartItem> cartLines) {
        if (cartLines.isEmpty()) {
            throw AppException.badRequest("Your cart is empty");
        }

        record PricedLine(Product product, int quantity, BigDecimal vatRate,
                           BigDecimal lineTotal, BigDecimal netAmount, UUID reservationId) {}

        List<PricedLine> priced = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;

        for (GuestCartItem line : cartLines) {
            Product product = products.findById(line.productId())
                    .filter(p -> !p.isDeleted())
                    .orElseThrow(() -> AppException.conflict("A product in your cart is no longer available"));

            UUID reservationId = inventory.reserve(product.getId(), line.quantity())
                    .orElseThrow(() -> AppException.conflict("Not enough stock for \"" + product.getName() + "\""));

            BigDecimal vatRate = tax.rateFor(product.getVatCategory());
            BigDecimal lineTotal = product.getPrice()
                    .multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            // Prices are gross (VAT-inclusive, matching cart/product display) — the
            // standard EU back-calculation to net: gross / (1 + rate).
            BigDecimal netAmount = lineTotal.divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);

            priced.add(new PricedLine(product, line.quantity(), vatRate, lineTotal, netAmount, reservationId));
            subtotal = subtotal.add(netAmount);
            total = total.add(lineTotal);
            currency = product.getCurrency();
        }

        // Shipping is folded INTO subtotal/total rather than added outside
        // them, so `subtotal + vatTotal == total` still holds exactly. Its
        // price is gross (VAT-inclusive) like every other price here, and it
        // carries the STANDARD rate — Estonia taxes delivery at the standard
        // rate regardless of what is in the parcel.
        DeliverySnapshot delivery = resolveDelivery(billing.deliveryCode(), currency);
        if (delivery != null) {
            BigDecimal shippingGross = delivery.price();
            BigDecimal shippingNet = shippingGross.divide(
                    BigDecimal.ONE.add(tax.rateFor("STANDARD")), 2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(shippingNet);
            total = total.add(shippingGross);
        }

        // Defined as the difference of two already-rounded sums, not summed
        // independently — guarantees subtotal + vatTotal == total exactly,
        // with no separate reconciliation step that could disagree.
        BigDecimal vatTotal = total.subtract(subtotal);

        Order order = orders.save(new Order(
                userId, idempotencyKey,
                billing.billingName(), billing.billingEmail(), billing.billingPhone(),
                billing.addressLine1(), billing.addressLine2(), billing.city(),
                billing.postalCode(), billing.country(),
                currency, subtotal, vatTotal, total, delivery));

        for (PricedLine p : priced) {
            orderLines.save(new OrderLine(order.getId(), p.product().getId(), p.product().getName(),
                    p.product().getPrice(), p.vatRate(), p.quantity(), p.product().getCurrency(),
                    p.lineTotal(), p.reservationId()));
        }

        orderEvents.save(new OrderEvent(order.getId(), OrderEvent.Type.CREATED));

        OrderResponse response = toResponse(order);
        sendConfirmationEmail(response);
        return response;
    }

    /**
     * Resolves the chosen delivery method into a SNAPSHOT (DECISIONS.md §2).
     * Read once, here, at purchase time — after this the order describes its
     * own delivery and never consults delivery_options again, so repricing or
     * retiring a method cannot rewrite a historical invoice.
     *
     * An inactive method is refused rather than silently accepted: it is off
     * the menu, and honouring a price the shop has withdrawn is exactly the
     * kind of thing a stale client tab would otherwise do.
     */
    private DeliverySnapshot resolveDelivery(String code, String orderCurrency) {
        if (code == null || code.isBlank()) {
            return null;
        }
        DeliveryOption option = deliveryOptions.findByCode(code)
                .filter(DeliveryOption::isActive)
                .orElseThrow(() -> AppException.badRequest("Unknown or unavailable delivery option: " + code));
        if (orderCurrency != null && !orderCurrency.equals(option.getCurrency())) {
            // Mixing currencies inside one total is silently wrong money.
            throw AppException.conflict("Delivery option currency does not match the order currency");
        }
        return new DeliverySnapshot(option.getCode(), option.getName(), option.getPrice(),
                option.getLeadTimeDaysMin(), option.getLeadTimeDaysMax());
    }

    /**
     * Best-effort, same as every other MailService caller — a downed SMTP
     * relay must not fail an order that already committed. Sent from HERE
     * (after the order transaction's own work, not inside it) so a mail
     * failure can never roll back a real order.
     */
    private void sendConfirmationEmail(OrderResponse order) {
        // Sent at checkout, BEFORE the payment result is known, so it must not
        // read like a completed purchase: payment is confirmed by a separate email.
        String body = "We have received your order " + order.reference() + ".\n\n"
                + OrderMailFormatter.summary(order)
                + "\n\nThis confirms that your order was placed. It does not mean your payment has gone "
                + "through yet — you will get a separate email as soon as the payment is confirmed.";
        mail.send(order.billingEmail(), "Order received", body, "order-confirmation");
    }

    /**
     * Scoped to the caller's own orders — never a global/admin list.
     *
     * Status is derived by folding events (DECISIONS.md §1), not a stored
     * column, so it can't be filtered at the SQL level — every candidate
     * order has to be resolved to a status first. That means filtering by
     * status MUST happen before paginating, not after: filtering a single
     * already-paged window (the previous bug here) both under-fills pages
     * and reports a totalCount that doesn't match the filtered set.
     */
    @Transactional(readOnly = true)
    public OrderListResponse listOrders(UUID userId, String statusFilter, Instant from, Instant to,
                                         int page, int size) {
        List<Order> dateFiltered = orders.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(o -> from == null || !o.getCreatedAt().isBefore(from))
                .filter(o -> to == null || !o.getCreatedAt().isAfter(to))
                .toList();

        List<UUID> ids = dateFiltered.stream().map(Order::getId).toList();
        Map<UUID, String> statusById = deriveStatuses(ids);
        Map<UUID, Integer> itemCountById = orderLines.findByOrderIdIn(ids).stream()
                .collect(Collectors.groupingBy(OrderLine::getOrderId, Collectors.summingInt(OrderLine::getQuantity)));

        List<OrderSummaryResponse> matched = dateFiltered.stream()
                .map(o -> new OrderSummaryResponse(o.getId(), statusById.get(o.getId()),
                        itemCountById.getOrDefault(o.getId(), 0), o.getTotal(), o.getCurrency(), o.getCreatedAt()))
                .filter(s -> statusFilter == null || statusFilter.equalsIgnoreCase(s.status()))
                .toList();

        int fromIndex = Math.min(page * size, matched.size());
        int toIndex = Math.min(fromIndex + size, matched.size());
        List<OrderSummaryResponse> pageContent = matched.subList(fromIndex, toIndex);

        return new OrderListResponse(pageContent, page, size, matched.size());
    }

    /** Prefills checkout for a returning customer — never trusted for anything but convenience. */
    @Transactional(readOnly = true)
    public Optional<CheckoutPrefillResponse> lastAddress(UUID userId) {
        return orders.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(o -> new CheckoutPrefillResponse(o.getBillingName(), o.getBillingPhone(),
                        o.getBillingAddressLine1(), o.getBillingAddressLine2(), o.getBillingCity(),
                        o.getBillingPostalCode(), o.getBillingCountry()));
    }

    /** Ownership is enforced here, not left to the caller — returns empty rather than someone else's order. */
    @Transactional(readOnly = true)
    public Optional<OrderResponse> getOrderDetail(UUID userId, UUID orderId) {
        return orders.findById(orderId)
                .filter(o -> o.getUserId() != null && o.getUserId().equals(userId))
                .map(this::toResponse);
    }

    /**
     * Cancel-before-shipping: an unpaid (CREATED)
     * order just releases its reservations, but a PAID order needs an actual
     * Stripe refund first — anything past that (PAYMENT_FAILED already
     * released its own stock, CANCELLED/REFUNDED already resolved, SHIPPED
     * needs a real-world return, not a button) is refused.
     *
     * Deliberately NOT @Transactional at this outer level: the PAID branch
     * makes a real network call to Stripe (payments.refundForOrder), and
     * PaymentService's own contract is that Stripe calls must never run
     * inside an open DB transaction (see its javadoc — same reasoning as
     * CheckoutController). Each DB step below is independently atomic
     * (InventoryService.release()'s claim, JpaRepository.save()'s own
     * per-call transaction) and idempotent, so a failure partway through —
     * e.g. the refund succeeds but this process dies before the REFUNDED
     * event is appended — leaves the order in a recoverable state: a retried
     * cancel just re-derives PAID, calls refundForOrder again, and Stripe
     * itself is idempotent on a PaymentIntent that's already refunded.
     */
    public OrderResponse cancelOrder(UUID userId, UUID orderId) {
        Order order = orders.findById(orderId)
                .filter(o -> o.getUserId() != null && o.getUserId().equals(userId))
                .orElseThrow(() -> AppException.notFound("Order not found"));

        String status = OrderEvent.deriveStatus(orderEvents.findByOrderIdOrderByOccurredAt(orderId));
        if (OrderEvent.Type.CREATED.name().equals(status)) {
            releaseReservations(orderId);
            orderEvents.save(new OrderEvent(orderId, OrderEvent.Type.CANCELLED));
        } else if (OrderEvent.Type.PAID.name().equals(status)) {
            payments.refundForOrder(orderId);
            orderEvents.save(new OrderEvent(orderId, OrderEvent.Type.REFUNDED));
        } else {
            throw AppException.conflict("This order can no longer be cancelled");
        }

        return toResponse(order);
    }

    private void releaseReservations(UUID orderId) {
        for (OrderLine line : orderLines.findByOrderIdOrderByCreatedAt(orderId)) {
            if (line.getReservationId() != null) {
                inventory.release(line.getReservationId());
            }
        }
    }

    /**
     * Called by PaymentResultConsumer once Stripe confirms the PaymentIntent
     * succeeded. Idempotent by construction: only acts
     * while the order is still CREATED, so RabbitMQ redelivering the same
     * message — which its at-least-once delivery guarantee makes a normal
     * occurrence, not a bug — just no-ops on the replay instead of
     * double-converting inventory or double-emailing the customer.
     */
    @Transactional
    public void markPaid(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> AppException.notFound("Order not found"));
        String status = OrderEvent.deriveStatus(orderEvents.findByOrderIdOrderByOccurredAt(orderId));
        if (!OrderEvent.Type.CREATED.name().equals(status)) {
            if (OrderEvent.Type.CANCELLED.name().equals(status)
                    || OrderEvent.Type.PAYMENT_FAILED.name().equals(status)) {
                // Money arrived for an order no longer waiting for it: cancelled while the
                // customer was still paying, or already reported failed. Nothing here can
                // reopen the order, so the payment has to be returned by hand, and this is
                // how staff find out.
                audit.record("payment.received_for_closed_order", null, "order " + orderId, null, null);
            }
            return;
        }

        for (OrderLine line : orderLines.findByOrderIdOrderByCreatedAt(orderId)) {
            if (line.getReservationId() != null) {
                inventory.convertToSold(line.getReservationId());
            }
        }
        orderEvents.save(new OrderEvent(orderId, OrderEvent.Type.PAID));
        sendPaymentReceivedEmail(order);
    }

    /**
     * The bill goes out with the payment confirmation. Issuing it can never fail
     * the payment (InvoiceService.issueForPayment does not throw); if there is no
     * document the customer still gets the email, and an admin can issue one by
     * hand from the order screen.
     */
    private void sendPaymentReceivedEmail(Order order) {
        OrderResponse response = toResponse(order);
        String body = "Your payment for order " + response.reference() + " was received. "
                + "Thank you for your purchase!\n\n" + OrderMailFormatter.summary(response)
                + "\n\nYour invoice is attached.";
        String plainBody = "Your payment for order " + response.reference() + " was received. "
                + "Thank you for your purchase!\n\n" + OrderMailFormatter.summary(response);

        Optional<BillingDocument> invoice = invoices.issueForPayment(order.getId());
        if (invoice.isPresent()) {
            try {
                byte[] pdf = invoices.readFile(invoice.get());
                mail.sendWithAttachment(order.getBillingEmail(), "Payment received", body,
                        "invoice-" + response.reference() + ".pdf", pdf, "application/pdf", "payment-received");
                return;
            } catch (IOException e) {
                // The file was just written; not being able to read it back is
                // unusual, but it must not cost the customer the confirmation.
                audit.record("invoice.attachment_failed", null, "order " + order.getId(), null, null);
            }
        }
        mail.send(order.getBillingEmail(), "Payment received", plainBody, "payment-received");
    }

    /** Mirror of markPaid for the declined/errored path — releases stock instead of converting it. */
    @Transactional
    public void markPaymentFailed(UUID orderId, String reason) {
        Order order = orders.findById(orderId).orElseThrow(() -> AppException.notFound("Order not found"));
        String status = OrderEvent.deriveStatus(orderEvents.findByOrderIdOrderByOccurredAt(orderId));
        if (!OrderEvent.Type.CREATED.name().equals(status)) {
            return;
        }

        releaseReservations(orderId);
        orderEvents.save(new OrderEvent(orderId, OrderEvent.Type.PAYMENT_FAILED));
        OrderResponse response = toResponse(order);
        String suffix = reason != null && !reason.isBlank() ? ": " + reason : "";
        mail.send(order.getBillingEmail(), "Payment failed",
                "Your payment for order " + response.reference() + " failed" + suffix
                        + ". You can try again from My Orders.\n\n" + OrderMailFormatter.summary(response),
                "payment-failed");
    }

    private Map<UUID, String> deriveStatuses(List<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<OrderEvent>> byOrder = orderEvents.findByOrderIdInOrderByOccurredAt(orderIds).stream()
                .collect(Collectors.groupingBy(OrderEvent::getOrderId, HashMap::new, Collectors.toList()));
        Map<UUID, String> result = new HashMap<>();
        for (UUID id : orderIds) {
            result.put(id, OrderEvent.deriveStatus(byOrder.getOrDefault(id, List.of())));
        }
        return result;
    }

    private OrderResponse toResponse(Order order) {
        List<OrderLineResponse> lines = orderLines.findByOrderIdOrderByCreatedAt(order.getId()).stream()
                .map(l -> new OrderLineResponse(l.getProductId(), l.getProductName(), l.getUnitPrice(),
                        l.getVatRate(), l.getQuantity(), l.getCurrency(), l.getLineTotal()))
                .toList();

        List<OrderEvent> rawEvents = orderEvents.findByOrderIdOrderByOccurredAt(order.getId());
        List<OrderEventResponse> events = rawEvents.stream()
                .map(e -> new OrderEventResponse(e.getEventType().name(), e.getOccurredAt()))
                .toList();
        String status = OrderEvent.deriveStatus(rawEvents);

        return new OrderResponse(order.getId(), OrderResponse.referenceFor(order.getId()),
                status, lines, events, order.getCurrency(),
                order.getSubtotal(), order.getVatTotal(), order.getTotal(), deliveryDetails(order),
                order.getBillingName(), order.getBillingEmail(), order.getBillingPhone(), order.getBillingAddressLine1(),
                order.getBillingAddressLine2(), order.getBillingCity(), order.getBillingPostalCode(),
                order.getBillingCountry(), order.getCreatedAt(), null);
    }

    /**
     * Estimated arrival is computed here from the order's own snapshotted
     * lead-time range and creation date, rather than stored — a stored date
     * would be stale the moment anything changed, and this is an estimate,
     * not a commitment. Calendar days, deliberately not working days: the
     * shop has no holiday calendar, and over-promising is worse than a
     * slightly conservative window.
     */
    private DeliveryDetails deliveryDetails(Order order) {
        if (order.getDeliveryCode() == null) {
            return null;
        }
        LocalDate placed = order.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return new DeliveryDetails(
                order.getDeliveryCode(), order.getDeliveryName(), order.getDeliveryPrice(),
                order.getDeliveryLeadDaysMin(), order.getDeliveryLeadDaysMax(),
                placed.plusDays(order.getDeliveryLeadDaysMin()),
                placed.plusDays(order.getDeliveryLeadDaysMax()));
    }
}
