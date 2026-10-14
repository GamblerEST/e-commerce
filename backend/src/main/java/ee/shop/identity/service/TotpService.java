package ee.shop.identity.service;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

/**
 * Wraps dev.samstevens.totp (RFC 6238). The raw base32 secret only ever
 * exists in memory long enough to build the QR code / otpauth URI and to
 * verify a code — TotpSecretCipher is what actually goes to the database.
 */
@Service
public class TotpService {

    private static final String ISSUER = "shop.ee";

    private final DefaultSecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier codeVerifier =
            new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
    private final TotpSecretCipher cipher;

    public TotpService(TotpSecretCipher cipher) {
        this.cipher = cipher;
    }

    public GeneratedSecret generateSecret() {
        String raw = secretGenerator.generate();
        return new GeneratedSecret(raw, cipher.encrypt(raw));
    }

    public String buildOtpAuthUri(String rawSecret, String accountEmail) {
        QrData data = new QrData.Builder()
                .label(accountEmail)
                .secret(rawSecret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        return data.getUri();
    }

    public boolean verify(String encryptedSecret, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String raw = cipher.decrypt(encryptedSecret);
        return codeVerifier.isValidCode(raw, code);
    }

    public record GeneratedSecret(String rawSecret, String encryptedSecret) {}
}
