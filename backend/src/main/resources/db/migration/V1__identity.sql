-- V1__identity.sql
-- Identity core: users, sessions, audit.
-- Catalog, inventory and orders arrive in later migrations. Flyway migrations
-- are append-only and immutable once applied — never edit a migration that has
-- run anywhere, add a new one.

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive text

-- ─────────────────────────────────────────────────────────────────────────────
-- users
--
-- Two design points that are load-bearing:
--
-- 1. email is CITEXT, not VARCHAR.
--    'Kati@example.ee' and 'kati@example.ee' are the same mailbox. If email is
--    VARCHAR, the UNIQUE index treats them as different and you get two accounts
--    for one person. Lowercasing in Java is not a fix — it relies on every code
--    path remembering to do it. CITEXT enforces it in the one place that cannot
--    be bypassed: the database.
--
-- 2. anonymized_at, not DELETE.
--    Orders reference this row and must survive 7 years (Estonian Accounting
--    Act). GDPR erasure therefore means scrubbing PII in place, not removing the
--    row. See the anonymize_user() function at the bottom.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    email               CITEXT,
    -- Argon2id encoded hash, e.g. $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>
    -- The parameters are embedded in the string, so raising cost factors later
    -- does not invalidate existing hashes — we rehash on next successful login.
    password_hash       TEXT,

    display_name        TEXT,

    role                TEXT NOT NULL DEFAULT 'CUSTOMER'
                            CHECK (role IN ('CUSTOMER', 'ADMIN')),

    email_verified_at   TIMESTAMPTZ,
    disabled_at         TIMESTAMPTZ,

    -- TOTP secret, encrypted at rest by the application (never plaintext).
    totp_secret_enc     TEXT,
    totp_enabled_at     TIMESTAMPTZ,

    -- GDPR erasure marker. When set, email/display_name/password_hash are NULL
    -- and this account can never log in again. The row survives only to keep
    -- order foreign keys valid.
    anonymized_at       TIMESTAMPTZ,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- An account is either live (has an email) or anonymized (has none).
    -- This CHECK makes the illegal state unrepresentable rather than merely
    -- discouraged. If a bug tries to anonymize without clearing the email,
    -- the write fails loudly instead of leaving PII behind silently.
    CONSTRAINT users_anonymized_has_no_pii CHECK (
        (anonymized_at IS NULL  AND email IS NOT NULL)
     OR (anonymized_at IS NOT NULL AND email IS NULL AND password_hash IS NULL)
    )
);

-- Partial unique index: only live accounts occupy an email address.
-- Anonymized rows have email = NULL and so do not collide, which means a user
-- can delete their account and later sign up again with the same address.
CREATE UNIQUE INDEX users_email_unique
    ON users (email)
    WHERE anonymized_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- oauth_identities
--
-- Separate table, not columns on users. A person may link Google today and
-- Apple tomorrow; columns would force a schema change and cap them at one.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE oauth_identities (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider      TEXT NOT NULL,           -- 'google'
    subject       TEXT NOT NULL,           -- provider's stable user id ('sub')
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (provider, subject)
);

CREATE INDEX oauth_identities_user_id_idx ON oauth_identities (user_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- refresh_tokens  — rotation with reuse detection
--
-- THE MECHANISM, because this is the part that is easy to get subtly wrong:
--
--   Login issues token A. family_id = F (new), parent = NULL.
--   Refresh with A  -> A is marked used_at=now, issues token B. family = F,
--                      parent = A.
--   Refresh with B  -> B used, issues C. family = F, parent = B.
--
--   Now suppose an attacker stole A and replays it after the real user has
--   already refreshed. A.used_at is already set. That is IMPOSSIBLE in honest
--   use — a rotated token is never presented twice. So the only explanations
--   are theft or a cloned client.
--
--   Response: revoke the ENTIRE family F. The attacker's token dies, and so
--   does the victim's current session, forcing a re-login. That is the correct
--   trade: a logged-out user is a minor annoyance, a hijacked session is not.
--
-- We store only sha256(token). A database dump therefore yields no usable
-- tokens. SHA-256 (not Argon2) is right here: the token is already 256 bits of
-- CSPRNG output, so there is nothing to brute-force, and we need this lookup to
-- be fast on every refresh.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE refresh_tokens (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    family_id      UUID NOT NULL,
    parent_id      UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,

    token_sha256   BYTEA NOT NULL UNIQUE,

    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,

    used_at        TIMESTAMPTZ,   -- set on rotation; second use = reuse = theft
    revoked_at     TIMESTAMPTZ,
    revoked_reason TEXT,          -- 'logout' | 'reuse_detected' | 'password_change' | 'admin'

    -- Session context, for the "your active sessions" screen and for forensics.
    user_agent     TEXT,
    ip             INET
);

CREATE INDEX refresh_tokens_user_idx   ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
-- Supports the cleanup job that prunes long-dead tokens.
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- One-time tokens: email verification, password reset.
--
-- Same table for both — they have identical mechanics (single use, short TTL,
-- hashed at rest) and differ only by purpose. A single table with a purpose
-- column beats two near-identical tables.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE one_time_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose       TEXT NOT NULL CHECK (purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET')),
    token_sha256  BYTEA NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX one_time_tokens_user_purpose_idx ON one_time_tokens (user_id, purpose);

-- ─────────────────────────────────────────────────────────────────────────────
-- recovery_codes — 2FA backup
--
-- Without these, a lost phone is a permanently lost account. Each code is
-- single-use and hashed (Argon2 here, not SHA-256: these are short, human-
-- typeable, and therefore genuinely brute-forceable, so we want slow hashing).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE recovery_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   TEXT NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX recovery_codes_user_idx ON recovery_codes (user_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- audit_log — append-only
--
-- You cannot investigate a breach you did not record. Every security-relevant
-- event lands here: logins, failures, password changes, role changes, refresh
-- reuse, GDPR erasures, admin actions.
--
-- No UPDATE, no DELETE. Enforced by the trigger below rather than by good
-- intentions — an audit log that application code can rewrite is not evidence.
--
-- actor_id is nullable and ON DELETE SET NULL: the log must outlive the user,
-- but 'who did this' becomes anonymous once they exercise erasure.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    action      TEXT NOT NULL,
    target_type TEXT,
    target_id   TEXT,
    ip          INET,
    user_agent  TEXT,
    detail      JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_log_actor_idx    ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX audit_log_action_idx   ON audit_log (action, occurred_at DESC);
CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at DESC);

CREATE OR REPLACE FUNCTION audit_log_is_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only; % is not permitted', TG_OP;
END;
$$;

CREATE TRIGGER audit_log_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();

-- ─────────────────────────────────────────────────────────────────────────────
-- updated_at maintenance
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION touch_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_touch_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ─────────────────────────────────────────────────────────────────────────────
-- anonymize_user() — GDPR Art. 17 erasure
--
-- Deliberately a database function, not Java. Erasure must be atomic and total;
-- if it lives in a service it will eventually be called from one code path and
-- forgotten in another. Here there is exactly one implementation.
--
-- Note what it does NOT touch: orders, order_lines, invoices. Those are retained
-- under the Accounting Act and carry their own snapshot of the billing name and
-- address. That separation is the entire reason this function can exist.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION anonymize_user(p_user_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    UPDATE users
       SET email           = NULL,
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

    -- Kill every session and credential. These are not accounting records and
    -- have no retention basis, so they are removed outright rather than scrubbed.
    DELETE FROM refresh_tokens   WHERE user_id = p_user_id;
    DELETE FROM one_time_tokens  WHERE user_id = p_user_id;
    DELETE FROM recovery_codes   WHERE user_id = p_user_id;
    DELETE FROM oauth_identities WHERE user_id = p_user_id;

    INSERT INTO audit_log (actor_id, action, target_type, target_id, detail)
    VALUES (NULL, 'user.anonymized', 'user', p_user_id::text,
            jsonb_build_object('basis', 'GDPR Art.17'));
END;
$$;
