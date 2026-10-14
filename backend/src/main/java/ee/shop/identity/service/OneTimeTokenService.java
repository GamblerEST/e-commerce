package ee.shop.identity.service;

import ee.shop.common.security.TokenCrypto;
import ee.shop.identity.model.OneTimeToken;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.OneTimeTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Shared issue/consume for email verification and password reset tokens. */
@Service
public class OneTimeTokenService {

    private final OneTimeTokenRepository tokens;

    public OneTimeTokenService(OneTimeTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional
    public String issue(User user, OneTimeToken.Purpose purpose, Duration ttl) {
        String raw = TokenCrypto.randomToken();
        tokens.save(new OneTimeToken(user.getId(), purpose, TokenCrypto.sha256(raw), Instant.now().plus(ttl)));
        return raw;
    }

    /**
     * Returns the owning userId if the token was valid for that purpose and
     * has just been consumed by this call. Empty means: unknown, wrong
     * purpose, already used, or expired — the caller cannot and should not
     * distinguish which (a stale link and a forged one look identical to the
     * client either way).
     */
    public Optional<UUID> consume(String rawToken, OneTimeToken.Purpose purpose) {
        byte[] hash = TokenCrypto.sha256(rawToken);
        int claimed = tokens.claim(hash, purpose.name());
        if (claimed == 0) {
            return Optional.empty();
        }
        return tokens.findByTokenSha256(hash).map(OneTimeToken::getUserId);
    }
}
