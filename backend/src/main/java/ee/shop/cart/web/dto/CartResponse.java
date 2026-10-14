package ee.shop.cart.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        List<CartItemResponse> items,
        int itemCount,
        BigDecimal totalPrice,
        String currency
) {}
