-- V24__email_blind_index.sql
-- Lets users.email be encrypted at rest without breaking login.
--
-- Why it was still in clear text
-- ─────────────────────────────
-- V20 encrypted every other piece of personal data and deliberately left the
-- email address alone, for a real reason: login looks accounts up BY it, and a
-- unique index enforces one live account per address. AES-GCM uses a random IV,
-- so the same address encrypts differently every time — an encrypted column
-- cannot be searched or uniquely indexed.
--
-- The standard answer is a blind index: keep the encrypted value for reading,
-- and store a deterministic KEYED hash beside it for finding. Lookups and the
-- uniqueness constraint both move to the hash. See EmailHasher for why it is an
-- HMAC rather than a plain digest.
--
-- The column is populated by the application at startup (PiiEncryptionBackfill),
-- not here: computing it needs the key, and the key deliberately never enters
-- the database.
ALTER TABLE users ADD COLUMN email_hash TEXT;

-- ─────────────────────────────────────────────────────────────────────────────
-- email stops being CITEXT, and loses its unique index.
--
-- Both existed to make the ADDRESS behave correctly: CITEXT so that
-- 'Kati@example.ee' and 'kati@example.ee' are one mailbox, and the unique index
-- so one live account owns an address. Neither claim survives encryption. The
-- column now holds base64 ciphertext, where case is significant data rather
-- than noise, and a random IV means the same address encrypts differently every
-- time — so the unique index would go on enforcing nothing while still looking
-- like the thing that enforces uniqueness. That is the more dangerous half:
-- a constraint that reads as a guarantee and is not one.
--
-- Case-insensitivity moves into EmailHasher, which lowercases before hashing;
-- uniqueness moves to the index below. Dropping these is what makes the blind
-- index the single place either rule lives.
DROP INDEX users_email_unique;
ALTER TABLE users ALTER COLUMN email TYPE TEXT;

-- The uniqueness rule moves here. Partial, exactly like the index it replaces:
-- only live accounts occupy an address, so an anonymised user frees theirs and
-- can sign up again.
CREATE UNIQUE INDEX users_email_hash_unique
    ON users (email_hash)
    WHERE anonymized_at IS NULL AND email_hash IS NOT NULL;

-- Finds rows the backfill has not reached yet.
CREATE INDEX users_email_hash_missing_idx
    ON users (id) WHERE email_hash IS NULL AND email IS NOT NULL;

COMMENT ON COLUMN users.email_hash IS
    'Keyed HMAC of the lowercased email (EmailHasher). The searchable half of the blind index; users.email holds the AES-GCM ciphertext.';

-- ─────────────────────────────────────────────────────────────────────────────
-- anonymize_user() has to clear the hash too.
--
-- Without this, an erased account keeps its blind index, the partial unique
-- index still counts it, and the person can never sign up again with their own
-- address — a GDPR erasure that quietly bans the customer.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION anonymize_user(p_user_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    UPDATE users
       SET email           = NULL,
           email_hash      = NULL,
           password_hash   = NULL,
           display_name    = NULL,
           totp_secret_enc = NULL,
           totp_enabled_at = NULL,
           anonymized_at   = now(),
           disabled_at     = COALESCE(disabled_at, now())
     WHERE id = p_user_id
       AND anonymized_at IS NULL;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'user % not found or already anonymized', p_user_id;
    END IF;

    DELETE FROM refresh_tokens   WHERE user_id = p_user_id;
    DELETE FROM one_time_tokens  WHERE user_id = p_user_id;
    DELETE FROM recovery_codes   WHERE user_id = p_user_id;
    DELETE FROM oauth_identities WHERE user_id = p_user_id;

    INSERT INTO audit_log (actor_id, action, target_type, target_id, detail)
    VALUES (NULL, 'user.anonymized', 'user', p_user_id::text,
            jsonb_build_object('basis', 'GDPR Art.17'));
END;
$$;

-- SECURITY DEFINER is not inherited by CREATE OR REPLACE; re-assert it, or the
-- function silently starts running with the caller's (smaller) rights.
ALTER FUNCTION anonymize_user(UUID) SECURITY DEFINER;
ALTER FUNCTION anonymize_user(UUID) SET search_path = public, pg_temp;
