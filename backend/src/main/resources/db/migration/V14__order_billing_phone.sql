-- V14__order_billing_phone.sql
-- Adds a phone number to the billing snapshot, alongside the rest of the
-- basic information checkout collects. Nullable at the DB level — migrations
-- are append-only and immutable once applied anywhere, so this cannot
-- retroactively become NOT NULL without a backfill for any order rows already
-- created before this column existed.
-- Required-ness for NEW orders is enforced at the application layer
-- (CheckoutRequest's @NotBlank), same as every other billing field's
-- validation already lives there rather than as a second source of truth.

ALTER TABLE orders ADD COLUMN billing_phone TEXT;
