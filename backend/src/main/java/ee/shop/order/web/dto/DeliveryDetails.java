package ee.shop.order.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How an order ships, as the customer sees it on the confirmation page.
 *
 * `estimatedFrom`/`estimatedTo` are DERIVED at read time from the order's
 * snapshotted lead-time range plus its own creation date — not stored. A
 * stored date would have to be either recomputed (pointless) or left stale
 * (misleading) forever, and the range is only ever an estimate anyway.
 *
 * Calendar days, not working days: this deliberately does not pretend to
 * know Estonian public holidays or a specific carrier's cut-off times, and a
 * promise the shop can't keep is worse than a slightly conservative one.
 */
public record DeliveryDetails(
        String code,
        String name,
        BigDecimal price,
        int leadDaysMin,
        int leadDaysMax,
        LocalDate estimatedFrom,
        LocalDate estimatedTo
) {}
