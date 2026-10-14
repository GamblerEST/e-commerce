package ee.shop.review.web.dto;

import java.util.List;

public record ReviewListResponse(List<ReviewResponse> items, int page, int size, long totalCount) {}
