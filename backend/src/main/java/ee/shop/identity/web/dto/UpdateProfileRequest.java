package ee.shop.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(@NotBlank @Size(max = 120) String displayName) {}
