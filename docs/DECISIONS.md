# Architecture decisions

Why this codebase's hard rules exist. Read this before arguing with one of them.

---

## 1. Orders are immutable

An order is a legal document. Estonian accounting law requires source documents be
retained for 7 years. `UPDATE orders SET status = 'shipped'` destroys the record of
what the order was before — you cannot answer "what did this look like on March 3rd"
and you cannot prove you didn't tamper with it.

Append to `order_events`. Derive current status by folding the event stream. Same
reason banks don't overwrite balances.

## 2. Order lines snapshot their prices

Product costs €50. Customer buys. Later you raise it to €60. If the order line joins
to `products` for its price, that customer's *historical invoice* now says €60. Your
books are fiction and your VAT return is wrong.

The order line copies, at purchase time: product name, unit price, VAT rate, currency.
`product_id` is kept only for linking ("show me this product again"), never for pricing.

Same logic for the billing address: it lives ON the order, not via a join to `users`.
This is what makes GDPR anonymization possible without corrupting invoices (§5).

## 3. Inventory: on_hand vs reserved

A single `stock_quantity` column guarantees an oversell. But note the *actual*
mechanism, because the obvious explanation is wrong:

`UPDATE products SET stock = stock - 1 WHERE stock >= 1` is **already atomic**.
Postgres row-locks it; the second concurrent transaction blocks, re-reads, sees 0,
and its WHERE fails. No oversell. Atomicity is not the problem.

**Duration is the problem.** Checkout is not instantaneous — the customer is
redirected to Stripe and fumbles their card for 90 seconds. So:

- Decrement at *payment confirmation* → two people both reach the Stripe page for the
  last item, both pay, one gets a refund and an apology. Oversold.
- Decrement at *checkout start* → abandoned carts lock inventory forever. Stock says
  0, warehouse has 40.

Reservations hold stock for the *length of the checkout window*, and expire
automatically if payment never lands. The TTL is the entire point.

## 4. Money is BigDecimal

`0.1 + 0.2 != 0.3` in IEEE 754. This is not academic — it's a rounding error on
every invoice, and they accumulate. `NUMERIC(12,2)` in Postgres, `BigDecimal` in Java,
explicit ISO-4217 currency on every amount.

VAT rates are configuration, never constants. Estonia's standard rate went 20% → 22%
(Jan 2024) → 24% (Jul 2025, now permanent). Reduced rates of 13% and 9% also exist.
A `VAT_RATE = 0.24` constant is a bug with a delay fuse.

## 5. GDPR erasure = anonymize, not DELETE

"Delete my account" from a customer with three completed orders:

| Data | Action | Legal basis |
|---|---|---|
| Login, email, password, sessions, marketing consent, browsing history | **Erase** | Consent withdrawn, no other basis |
| Invoices, order lines, payment records | **Retain 7 years, untouched** | Legal obligation (Accounting Act) — GDPR Art. 17(3)(b) explicitly exempts this |
| `users` row | **Anonymize in place** — email NULL, name NULL, `anonymized_at` set | Order FKs must survive; you cannot cascade-delete a financial record |

While an order is *in flight* you cannot erase at all — you need the address to ship it
(Art. 17(3), active contract).

This is why PII must be **structurally separable** from the transaction record. If
orders read the customer's name via a join to `users`, then anonymizing the user
corrupts your invoices — and you've broken tax law trying to comply with privacy law.

Implemented as `anonymize_user(uuid)`, a `SECURITY DEFINER` function with a pinned
`search_path`. Deliberately in the database, not a Java service: erasure must be
atomic and total, and if it lives in a service it will eventually be called from one
code path and forgotten in another.

## 6. Refresh token families and reuse detection

Rotation alone isn't enough. The mechanism:

```
login    → token A   family=F  parent=null
refresh  → token B   family=F  parent=A    (A.used_at set)
refresh  → token C   family=F  parent=B    (B.used_at set)
```

An honest client never presents a rotated token twice. So if token A shows up again,
it was captured. We cannot tell attacker from victim, so we **revoke the entire
family F** — both sessions die and the real user re-authenticates. A logged-out user
is a minor annoyance; a silently hijacked session is not.

**The claim must be atomic.** The naive version is a check-then-act race — the exact
same bug class as the inventory oversell:

```java
var t = repo.findByHash(h);        // both threads read
if (t.isUsed()) throw theft;       // both see used=false
t.setUsedAt(now); repo.save(t);    // both proceed  ← detection defeated
```

Instead: `UPDATE ... SET used_at=now() WHERE token_sha256=? AND used_at IS NULL`.
Returns 1 = won the race. Returns 0 = look up why (unknown / used / revoked / expired).

**Consequence for the frontend:** it MUST single-flight `/auth/refresh` — one in-flight
call, all other pending requests await the same promise. Fire two concurrently and you
log yourself out. Do **not** "solve" this with a grace window that accepts recently-used
tokens; that reopens the exact replay hole this exists to close.

## 7. Argon2id, via DelegatingPasswordEncoder

BCrypt is CPU-hard but only ~4 KB memory-hard, so a GPU runs ~100k guesses in parallel.
Argon2id needs 19 MiB *per guess* — a 24 GB GPU manages ~1,200. That multiplier is the
whole point. BCrypt isn't broken; Argon2id is simply better and we have no legacy hashes.

`DelegatingPasswordEncoder` prefixes hashes: `{argon2}$argon2id$v=19$m=19456,...`.
That prefix is the escape hatch — when Argon2 is superseded, old hashes keep verifying
and users upgrade transparently on next login. A bare `Argon2PasswordEncoder` leaves
exactly one migration path: email everyone a reset link.

Argon2 parameters are encoded in each hash, so raising cost factors later is safe and
backwards compatible.

## 8. Audit log: capability, not policy

The append-only trigger stops *bugs*. It does not stop an *attacker* — anyone with SQL
execution as the table owner can `DROP TRIGGER` and rewrite history.

So the app connects as `shop_runtime` (role `shop_app`), which holds `INSERT` and
`SELECT` on `audit_log` and nothing else. Even total SQL injection cannot alter a row,
because the role lacks the privilege.

**`TRUNCATE` is the trap.** It is not covered by `DELETE` permission and it does not
fire row-level triggers. A table defended only by a `BEFORE DELETE` trigger can still
be wiped. Withholding the privilege is the only real defence.

Verified by attacking it: UPDATE, DELETE, TRUNCATE, DROP TRIGGER, ALTER OWNER,
GRANT-to-self, and DROP TABLE all fail as `shop_runtime`, while legitimate INSERTs work.

Still to add (Phase 9): hash-chaining (each row stores `sha256(previous row)`, so
tampering is *detectable* even by someone who does own the DB) and off-box shipping
(an attacker who owns the VPS owns the log proving they were there).

## 9. Config has no defaults

```yaml
url: ${DB_URL}                        # no fallback — app refuses to boot
password: ${db_password:${DB_PASSWORD}}  # NOT a default: a source chain
```

A friendly-looking `${DB_URL:jdbc:postgresql://localhost:5432/shop}` is a landmine.
Deploy with a typo'd env var and the app doesn't crash — it starts perfectly, connects
to nothing, Flyway creates a fresh empty schema, and the store serves zero products
while every log line says `Started ShopApplication`. **Silence is the worst failure mode.**

The password looks like it has a default but doesn't: it's "try the Docker secret, else
the env var." Both external. Neither present ⇒ still fails to start.

## 10. Testcontainers, never H2

The schema uses `citext`, `INET`, `JSONB`, partial indexes, and plpgsql triggers. H2's
"PostgreSQL mode" fakes some and silently mishandles the rest. A green suite against H2
tells you nothing about the database you actually deploy.

Corollary: the migrations themselves are covered by every test that touches the DB.

## 11. CSRF is disabled — and that expires

Safe *today* because the access token rides in an `Authorization` header. A header is
not sent automatically by the browser on a cross-site request, so there is no ambient
credential for a malicious form-post to abuse.

**This stops being true the moment the refresh token moves into a cookie (Phase 3).**
Cookies ARE sent automatically cross-site — that is precisely the CSRF precondition.
When we add that cookie: `SameSite=Strict`, `HttpOnly`, `Secure`, and CSRF protection
re-enabled on `/auth/refresh`.
