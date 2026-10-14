package ee.shop.cart.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Priced from the LIVE product row, not a snapshot — see CartItem's javadoc. */
public record CartItemResponse(
        UUID productId,
        String name,
        String slug,
        BigDecimal unitPrice,
        String currency,
        String thumbnailUrl,
        int quantity,
        BigDecimal lineTotal
) {}
