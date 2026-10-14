package ee.shop.identity.web.dto;

import java.util.List;

/** Recovery codes are shown here ONCE — only their Argon2 hashes persist. */
public record TotpVerifyResponse(List<String> recoveryCodes) {}
