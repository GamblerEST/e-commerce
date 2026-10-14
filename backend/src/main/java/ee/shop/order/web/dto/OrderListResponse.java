package ee.shop.order.web.dto;

import java.util.List;

public record OrderListResponse(List<OrderSummaryResponse> items, int page, int size, long totalCount) {}
