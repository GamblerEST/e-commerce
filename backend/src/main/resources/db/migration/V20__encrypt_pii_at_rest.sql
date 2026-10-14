-- V20__encrypt_pii_at_rest.sql
-- Personal data encrypted at rest.
--
-- The application encrypts these columns with AES-256-GCM before they are
-- written (EncryptedStringConverter / FieldCipher). This migration only makes
-- the SCHEMA able to hold ciphertext; it does not itself encrypt anything,
-- because the key lives in the application, never in the database. Handing
-- Postgres the key so it could encrypt its own columns would defeat the point
-- of the exercise: an attacker with the database would then have both halves.
--
-- What is covered and what is not
-- ───────────────────────────────
-- COVERED: every free-text personal detail a customer supplies — the name,
-- email, phone and full postal address snapshotted onto an order, and the
-- display name on an account.
--
-- NOT COVERED, deliberately, each for a specific reason:
--
--   users.email          Login looks accounts up BY this value, and a unique
--                        index enforces one live account per address. AES-GCM
--                        uses a random IV, so the same address encrypts to a
--                        different string every time — it cannot be indexed
--                        or searched. Encrypting it needs a blind index (a
--                        keyed HMAC in a second column) to look up by, which
--                        is a larger change than this migration.
--   users.password_hash  Argon2id already. A hash is strictly better than
--                        encryption here: there is no key that turns it back
--                        into a password.
--   refresh tokens       Stored as SHA-256 digests, same reasoning.
--   totp_secret_enc      Already AES-GCM, since V1.
--   order amounts        NUMERIC(12,2) with CHECK (>= 0) constraints the
--                        database enforces. Encrypting them means TEXT, which
--                        drops those constraints and moves all money
--                        arithmetic into Java — trading a guarantee the
--                        database makes for one the application promises.
--                        See DECISIONS.md and the README section.
--
-- Column widths: these are all TEXT already except billing_country, which is
-- VARCHAR(2) and cannot hold a ~90-character ciphertext.

-- A 2-letter country code becomes ciphertext like any other address line.
ALTER TABLE orders ALTER COLUMN billing_country TYPE TEXT;

-- The application writes ciphertext prefixed with 'enc:v1:'. This index makes
-- "are there any rows still in clear text?" a cheap query rather than a full
-- scan of a growing table:
--     SELECT count(*) FROM orders WHERE billing_name NOT LIKE 'enc:v1:%';
CREATE INDEX orders_billing_name_unencrypted_idx
    ON orders (id)
    WHERE billing_name NOT LIKE 'enc:v1:%';

CREATE INDEX users_display_name_unencrypted_idx
    ON users (id)
    WHERE display_name IS NOT NULL AND display_name NOT LIKE 'enc:v1:%';

COMMENT ON COLUMN orders.billing_name IS
    'AES-256-GCM ciphertext (enc:v1: prefix). Encrypted by the application; the key is app.security.field.key, never in this database.';
COMMENT ON COLUMN orders.billing_address_line1 IS
    'AES-256-GCM ciphertext (enc:v1: prefix). See orders.billing_name.';
COMMENT ON COLUMN users.display_name IS
    'AES-256-GCM ciphertext (enc:v1: prefix) for rows written after V20. See orders.billing_name.';
