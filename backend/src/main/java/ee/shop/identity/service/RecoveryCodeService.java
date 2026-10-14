package ee.shop.identity.service;

import ee.shop.identity.model.RecoveryCode;
import ee.shop.identity.repository.RecoveryCodeRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 2FA backup codes. Deliberately does NOT use dev.samstevens.totp's own
 * RecoveryCodeGenerator — that class generates from java.util.Random, a
 * predictable LCG (see TokenCrypto's javadoc for why that's disqualifying
 * for anything security-sensitive). This generates from SecureRandom instead.
 */
@Service
public class RecoveryCodeService {

    private static final int CODE_COUNT = 10;
    private static final int GROUP_LENGTH = 5;

    // Excludes visually ambiguous characters (0/O, 1/I/L) — these are
    // human-typed from a printed/saved list, not pasted.
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RecoveryCodeRepository codes;
    private final PasswordEncoder passwordEncoder;

    public RecoveryCodeService(RecoveryCodeRepository codes, PasswordEncoder passwordEncoder) {
        this.codes = codes;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Replaces every existing code for this user with a fresh set. Called
     * once on TOTP enrollment; the raw codes are returned ONCE and never
     * stored — only their Argon2 hashes persist.
     */
    @Transactional
    public List<String> regenerate(UUID userId) {
        codes.deleteAllForUser(userId);

        List<String> raw = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = generateCode();
            raw.add(code);
            codes.save(new RecoveryCode(userId, passwordEncoder.encode(code)));
        }
        return raw;
    }

    /**
     * Hashed codes can't be looked up by equality, so this fetches every
     * unconsumed code for the user and checks each with Argon2's matches().
     * The actual single-use guarantee comes from claim()'s atomic UPDATE,
     * not from this scan — two concurrent logins presenting the SAME
     * recovery code both find the same candidate row here, but only one
     * wins the claim.
     */
    @Transactional
    public boolean consume(UUID userId, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        for (RecoveryCode code : codes.findByUserIdAndConsumedAtIsNull(userId)) {
            if (passwordEncoder.matches(candidate, code.getCodeHash())) {
                return codes.claim(code.getId()) == 1;
            }
        }
        return false;
    }

    /** Recovery codes are meaningless once 2FA is off — see TotpController.disable. */
    @Transactional
    public void clearAll(UUID userId) {
        codes.deleteAllForUser(userId);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(GROUP_LENGTH * 2 + 1);
        for (int i = 0; i < GROUP_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        sb.append('-');
        for (int i = 0; i < GROUP_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
