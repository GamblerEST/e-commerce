package ee.shop.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * Montonio's notification body: one signed token, about an order or a refund.
 *
 * Unlike the shop's own request bodies this one tolerates unknown fields. Montonio
 * writes it, not a client of ours, and a field added on their side would otherwise
 * fail every notification with a 400 for the two days they retry. Nothing outside
 * the signed token is read, so an extra field cannot change what happens.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankLinkNotificationRequest(
        @Size(max = 8192) String orderToken,
        @Size(max = 8192) String refundToken
) {}
