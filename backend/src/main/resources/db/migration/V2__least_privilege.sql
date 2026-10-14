-- V2__least_privilege.sql
--
-- Until now the application connects as the database owner, which means it can
-- DROP the audit_log trigger and rewrite history. The trigger protects us from
-- our own bugs; it does not protect us from an attacker with SQL execution.
--
-- Fix: the application gets a role that is *incapable* of modifying the audit
-- log, rather than merely forbidden from doing so. Capability beats policy —
-- a permission you do not hold cannot be abused, forgotten, or bypassed by a
-- code path someone added on a Friday.
--
-- Three roles:
--   shop            — owner. Runs migrations. Used by Flyway ONLY.
--   shop_app        — runtime. Full DML on business tables, INSERT-only on audit_log.
--   shop_readonly   — analytics / debugging. SELECT only, no PII columns later.

-- ─────────────────────────────────────────────────────────────────────────────
-- Roles
--
-- NOLOGIN "group" roles hold the grants; the LOGIN roles inherit from them.
-- This is the standard Postgres pattern: permissions attach to the group, so
-- rotating a password or adding a second app user does not mean re-granting
-- everything.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shop_app') THEN
        CREATE ROLE shop_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shop_readonly') THEN
        CREATE ROLE shop_readonly NOLOGIN;
    END IF;
END
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- shop_app: the runtime role
-- ─────────────────────────────────────────────────────────────────────────────
GRANT USAGE ON SCHEMA public TO shop_app;

-- Full DML on ordinary business tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON
    users,
    oauth_identities,
    refresh_tokens,
    one_time_tokens,
    recovery_codes
TO shop_app;

-- The audit log: INSERT and SELECT. Nothing else.
--
-- Note what is absent: UPDATE, DELETE, TRUNCATE. Even with total SQL injection,
-- an attacker running as shop_app CANNOT alter or remove an audit row, because
-- the role does not possess the privilege. They also cannot DROP the trigger,
-- because they do not own the table.
--
-- TRUNCATE deserves its own mention: it is NOT covered by DELETE permission and
-- it does NOT fire row-level triggers. A table protected only by a BEFORE DELETE
-- trigger can still be wiped by TRUNCATE. Withholding the privilege is the only
-- real defence. This is a genuinely common oversight.
GRANT SELECT, INSERT ON audit_log TO shop_app;
GRANT USAGE ON SEQUENCE audit_log_id_seq TO shop_app;

-- anonymize_user() is SECURITY DEFINER below, so shop_app may call it even
-- though it performs writes shop_app could not otherwise perform on its own.
GRANT EXECUTE ON FUNCTION anonymize_user(UUID) TO shop_app;

-- ─────────────────────────────────────────────────────────────────────────────
-- shop_readonly: analytics, debugging, the BI tool you will inevitably want
-- ─────────────────────────────────────────────────────────────────────────────
GRANT USAGE ON SCHEMA public TO shop_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO shop_readonly;

-- Applies to tables created by FUTURE migrations too, so we do not have to
-- remember to re-grant every time we add a table. Without this line, the
-- products table added in Phase 4 would be invisible to shop_readonly and
-- someone would "fix" it by granting superuser.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO shop_readonly;

-- Same idea for shop_app, minus the audit_log exception which we set explicitly
-- above and must keep setting explicitly for any future append-only table.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO shop_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO shop_app;

-- ─────────────────────────────────────────────────────────────────────────────
-- anonymize_user must run with the owner's rights, not the caller's.
--
-- It DELETEs from refresh_tokens etc. and INSERTs into audit_log — fine for
-- shop_app. But making it SECURITY DEFINER means the erasure logic keeps working
-- even if we later tighten shop_app further, and it guarantees the audit entry
-- is written by a path the caller cannot skip.
--
-- SECURITY DEFINER functions are a classic privilege-escalation vector: if an
-- attacker can create a malicious function named e.g. `now()` in a schema that
-- appears earlier in search_path, the definer function will call THEIR code with
-- owner privileges. Pinning search_path shuts that door.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER FUNCTION anonymize_user(UUID) SECURITY DEFINER;
ALTER FUNCTION anonymize_user(UUID) SET search_path = public, pg_temp;

-- The login role the application actually uses.
-- Password is set at deploy time, not here — a password in a migration file is
-- a password in git forever.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shop_runtime') THEN
        CREATE ROLE shop_runtime LOGIN IN ROLE shop_app;
    END IF;
END
$$;
