package ee.shop.identity.web.dto;

/** secret is the manual-entry fallback for when a user can't scan the QR code. */
public record TotpSetupResponse(String otpauthUri, String secret) {}
