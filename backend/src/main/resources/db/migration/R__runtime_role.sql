-- R__runtime_role.sql  (REPEATABLE — re-runs whenever its checksum changes)
--
-- Gives the runtime login role its password, so the application can stop
-- connecting as the database owner.
--
-- Why this existed as a hole
-- ─────────────────────────
-- V2__least_privilege.sql built the whole least-privilege structure — shop_app
-- (NOLOGIN, holds the grants), shop_runtime (LOGIN, inherits them) — and
-- DECISIONS.md §8 describes the application as connecting through it. It never
-- did. DB_USER was the bootstrap superuser, which owns every table and holds
-- TRUNCATE on audit_log, so the documented defence ("even total SQL injection
-- cannot alter an audit row") was inert. Verified before the fix: TRUNCATE
-- audit_log SUCCEEDED as the application's own database user, and was denied
-- as shop_app.
--
-- shop_runtime was created without a password, which is very likely why nobody
-- switched to it. This closes that.
--
-- Why REPEATABLE rather than versioned
-- ────────────────────────────────────
-- The password arrives as a Flyway placeholder from a Docker secret. A
-- versioned migration would have its checksum baked in at first run, so
-- rotating the secret would then fail validation and require a manual repair.
-- A repeatable migration re-applies precisely WHEN the value changes, which is
-- the behaviour rotation needs.
--
-- Flyway runs as the admin user (spring.flyway.user), not as shop_runtime —
-- the runtime role deliberately cannot ALTER ROLE, or it could grant itself
-- back the privileges this whole arrangement exists to withhold.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shop_runtime') THEN
        CREATE ROLE shop_runtime LOGIN IN ROLE shop_app;
    END IF;
END
$$;

-- format(%L) quotes and escapes the value as a literal, so a password
-- containing a quote cannot terminate the statement early.
DO $$
BEGIN
    EXECUTE format('ALTER ROLE shop_runtime WITH LOGIN PASSWORD %L', '${runtime_password}');
END
$$;

-- Idempotent: re-granting an existing membership is a no-op.
GRANT shop_app TO shop_runtime;

-- The role must be able to reach the schema at all. USAGE was granted to
-- shop_app in V2; this is belt-and-braces for a database restored from an
-- older dump where that grant was missed.
GRANT USAGE ON SCHEMA public TO shop_app;
