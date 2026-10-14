package ee.shop.order.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** status is DERIVED (folded order_events), never a stored column — DECISIONS.md §1. */
public record OrderSummaryResponse(
        UUID id,
        String status,
        int itemCount,
        BigDecimal total,
        String currency,
        Instant createdAt
) {}
