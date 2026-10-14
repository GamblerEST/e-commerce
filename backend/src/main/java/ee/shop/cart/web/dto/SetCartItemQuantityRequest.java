package ee.shop.cart.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record SetCartItemQuantityRequest(
        @Min(1) @Max(99) int quantity
) {}
