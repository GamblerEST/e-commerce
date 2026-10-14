package ee.shop.identity.web.dto;

import ee.shop.identity.model.UserAddress;

import java.util.UUID;

public record AddressResponse(
        UUID id,
        String label,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String city,
        String postalCode,
        String country,
        boolean isDefault
) {
    public static AddressResponse of(UserAddress a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getRecipientName(), a.getPhone(),
                a.getLine1(), a.getLine2(), a.getCity(), a.getPostalCode(), a.getCountry(),
                a.isDefaultAddress());
    }
}
