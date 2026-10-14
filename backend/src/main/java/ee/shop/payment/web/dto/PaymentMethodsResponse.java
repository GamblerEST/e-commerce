package ee.shop.payment.web.dto;

import java.util.List;

/**
 * What checkout can offer right now. Bank logos are left out on purpose: showing
 * them would load images from a third-party host before the customer has chosen to
 * pay that way.
 */
public record PaymentMethodsResponse(boolean card, boolean bankLink, List<Bank> banks) {

    public record Bank(String code, String name) {}
}
