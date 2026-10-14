package ee.shop.delivery.web.dto;

import ee.shop.delivery.model.DeliveryOption;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A delivery option as the ADMIN screen needs it.
 *
 * Separate from DeliveryOptionResponse because that one is the PUBLIC price
 * list: it deliberately carries no id, no `active` flag and no sort order,
 * since a shopper is only ever offered options that are already active and
 * already ordered.
 *
 * The admin screen needs all three. Without the id in particular, the
 * update and delete endpoints are unreachable from a UI — they take
 * /{id}, and nothing in the public payload supplies one.
 */
public record AdminDeliveryOptionView(
        UUID id,
        String code,
        String name,
        String carrier,
        BigDecimal price,
        String currency,
        int leadTimeDaysMin,
        int leadTimeDaysMax,
        boolean active,
        int sortOrder
) {
    public static AdminDeliveryOptionView of(DeliveryOption o) {
        return new AdminDeliveryOptionView(o.getId(), o.getCode(), o.getName(), o.getCarrier(),
                o.getPrice(), o.getCurrency(), o.getLeadTimeDaysMin(), o.getLeadTimeDaysMax(),
                o.isActive(), o.getSortOrder());
    }
}
