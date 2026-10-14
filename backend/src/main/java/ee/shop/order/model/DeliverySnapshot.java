package ee.shop.order.model;

import java.math.BigDecimal;

/**
 * The chosen delivery method as it was AT PURCHASE TIME, copied onto the
 * order (DECISIONS.md §2 — the same rule that makes order lines snapshot
 * their prices).
 *
 * An order never joins back to delivery_options to describe how it shipped:
 * an admin renaming "Courier to your door", repricing it, or retiring it must
 * not rewrite what a customer agreed to six months ago.
 *
 * `null` is a legitimate value — orders placed before delivery options
 * existed have none, and inventing one to backfill a financial record would
 * be fabricating history.
 */
public record DeliverySnapshot(
        String code,
        String name,
        /** GROSS, VAT-inclusive — same convention as product prices. */
        BigDecimal price,
        int leadDaysMin,
        int leadDaysMax
) {
    public static final DeliverySnapshot NONE = null;
}
