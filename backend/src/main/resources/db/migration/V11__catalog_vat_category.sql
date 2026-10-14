-- V11__catalog_vat_category.sql
-- Which VAT bucket a product belongs to (app.tax.rates in application.yml
-- holds the actual RATE VALUES — this column never stores a rate, only a
-- category name, so the rate can change without touching every product).
--
-- CHECK enumerates the same categories as app.tax.rates. These track
-- Estonian VAT LAW categories (which barely ever change) rather than the
-- rate values themselves (which have changed three times in 18 months —
-- see DECISIONS.md §4) — a DB-level enum here is not the same mistake as a
-- hardcoded rate constant would be.
ALTER TABLE products
    ADD COLUMN vat_category TEXT NOT NULL DEFAULT 'STANDARD'
        CHECK (vat_category IN ('STANDARD', 'REDUCED_9', 'REDUCED_13', 'ZERO'));
