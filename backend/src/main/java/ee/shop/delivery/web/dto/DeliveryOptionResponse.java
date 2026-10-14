package ee.shop.delivery.web.dto;

import ee.shop.delivery.model.DeliveryOption;

import java.math.BigDecimal;

/** What a shopper sees when choosing how their order ships. */
public record DeliveryOptionResponse(
        String code,
        String name,
        String carrier,
        BigDecimal price,
        String currency,
        int leadTimeDaysMin,
        int leadTimeDaysMax
) {
    public static DeliveryOptionResponse of(DeliveryOption o) {
        return new DeliveryOptionResponse(o.getCode(), o.getName(), o.getCarrier(), o.getPrice(),
                o.getCurrency(), o.getLeadTimeDaysMin(), o.getLeadTimeDaysMax());
    }
}
