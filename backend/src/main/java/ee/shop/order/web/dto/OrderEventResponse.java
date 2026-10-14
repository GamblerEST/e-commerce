package ee.shop.order.web.dto;

import java.time.Instant;

public record OrderEventResponse(String type, Instant occurredAt) {}
