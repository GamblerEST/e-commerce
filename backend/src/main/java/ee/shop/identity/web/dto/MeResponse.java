package ee.shop.identity.web.dto;

import java.util.UUID;

public record MeResponse(UUID userId, String email, String displayName, String role, boolean totpEnabled) {}
