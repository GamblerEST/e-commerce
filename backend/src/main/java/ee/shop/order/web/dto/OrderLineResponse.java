package ee.shop.order.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineResponse(
        UUID productId,
        String productName,
        BigDecimal unitPrice,
        BigDecimal vatRate,
        int quantity,
        String currency,
        BigDecimal lineTotal
) {}
