package ee.shop.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

public record TotpVerifyRequest(@NotBlank String code) {}
