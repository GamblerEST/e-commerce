-- V16__support_role.sql
-- Adds a second privileged role below ADMIN.
--
-- SUPPORT can see the things needed to help a customer — orders, accounts,
-- the review moderation queue — but cannot change what the shop sells, move
-- money, or grant roles. That split exists so the everyday "why hasn't my
-- order shipped" work doesn't require hand out an account that can also
-- delete the catalogue or promote itself.
--
-- The CHECK constraint is recreated rather than left permissive on purpose:
-- an unconstrained role column means a typo ('ADMINN') silently becomes a
-- role that matches no authority check, failing open into "logged in but
-- powerless" instead of loudly rejecting the write.

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('CUSTOMER', 'SUPPORT', 'ADMIN'));
