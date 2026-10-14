package ee.shop.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The signed result a customer's browser brings back from the bank. */
public record BankLinkReturnRequest(@NotBlank @Size(max = 8192) String orderToken) {}
