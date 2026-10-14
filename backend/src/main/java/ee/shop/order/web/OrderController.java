package ee.shop.order.web;

import ee.shop.common.error.AppException;
import ee.shop.identity.model.User;
import ee.shop.order.service.OrderService;
import ee.shop.order.web.dto.CheckoutPrefillResponse;
import ee.shop.order.web.dto.OrderListResponse;
import ee.shop.order.web.dto.OrderResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Order history is account-only — no guest equivalent, unlike Cart/Checkout.
 * A guest has no stable identity to look orders up by once the browser
 * session ends, so this whole controller sits behind the default
 * anyRequest().authenticated() rule (no permitAll needed in SecurityConfig).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public OrderListResponse list(Authentication authentication,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        UUID userId = currentUserId(authentication);
        int boundedSize = Math.min(Math.max(size, 1), 100);
        return orderService.listOrders(userId, status, from, to, Math.max(page, 0), boundedSize);
    }

    /** Prefill convenience for a returning customer's next checkout — 204 if they have no prior order. */
    @GetMapping("/last-address")
    public ResponseEntity<CheckoutPrefillResponse> lastAddress(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        return orderService.lastAddress(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public OrderResponse detail(Authentication authentication, @PathVariable UUID id) {
        UUID userId = currentUserId(authentication);
        // 404, not 403, for someone else's order — confirming an order ID
        // even EXISTS to a non-owner is its own small information leak.
        return orderService.getOrderDetail(userId, id)
                .orElseThrow(() -> AppException.notFound("Order not found"));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(Authentication authentication, @PathVariable UUID id) {
        UUID userId = currentUserId(authentication);
        return orderService.cancelOrder(userId, id);
    }

    private UUID currentUserId(Authentication authentication) {
        return ((User) authentication.getPrincipal()).getId();
    }
}
