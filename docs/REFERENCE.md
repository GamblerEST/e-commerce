# Reference

Detail that is too long for the README. Start with the [README](../README.md).

- [Local demo](#local-demo), including [Stripe test cards](#stripe-test-cards)
  and [Bank-link test payments](#bank-link-test-payments)
- [Feature walkthrough](#feature-walkthrough)
- [Full setup notes](#setup)
- [Running the tests](#running-the-tests)
- [Performance analysis report](#performance-analysis-report)
- [Encryption at rest](#encryption-at-rest)
- [Search engines and link previews](#search-engines-and-link-previews)
- [Usage: key endpoints](#usage-key-endpoints)
- [Database notes](#database-notes)

## Local demo

The frontend (`frontend/`) covers the whole thing —
register, login (incl. 2FA setup/disable), Google OAuth, forgot/reset
password, account management, browsing/searching the catalog, cart, checkout,
Stripe payment, and order history — without needing to hand-craft API
requests. Built with React + TypeScript + Vite.

```bash
# 1. Backend stack (if not already running) — see "Setup" above.
docker compose up -d --build

# 2. Frontend dev server, in a second terminal.
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. That's it — Vite proxies `/api` and
`/product-images` straight to the backend (`vite.config.ts`), so the browser
sees one origin and everything (cookies included) just works.

**What you can actually do:**
- Register an account, log in, log out. Reload the page mid-session — the
  session survives (silent refresh via the `refresh_token` cookie).
- Browse the catalog: filter by category, brand, and product attributes
  (color/material/size/… — narrowed to whatever's actually available in the
  selected category via `GET /products/facets`, not a fixed list), price
  range, sort, type-ahead search, numbered pagination (backed by a keyset
  cursor, not `OFFSET` — see `ProductSearchService`), open a product for full
  detail (images, metric+imperial dimensions, attributes).
- **Account page** (Navbar → your name, once logged in): edit your display
  name, change your password (requires the current one; signs you out
  everywhere afterward), and manage 2FA — set up with a real scannable QR
  code, or disable (requires a current code, so an active session alone
  can't turn it off).
- **Forgot/reset password**: `/forgot-password` → real page, real flow. Local
  `docker compose up` always routes outbound mail to a local Mailpit catcher
  (`docker-compose.override.yml`) — view anything sent at
  http://localhost:8025, nothing ever reaches a real inbox. The UI shows the
  same confirmation either way (enumeration-safe by design) whether or not
  Mailpit actually caught anything — `/reset-password?token=...` is a real
  page regardless, showing a clear success or invalid/expired state, never a
  blank one. Same story for `/verify-email?token=...`. Seeing a REAL email
  arrive requires deliberately swapping in real SMTP credentials
  (`deploy/secrets/mail_password` + `MAIL_HOST`/`MAIL_PORT` env) — a
  temporary, manual swap, never the local default (see
  `docker-compose.override.yml`'s comment on why).
- "Continue with Google" is wired up end-to-end but needs a real
  `GOOGLE_CLIENT_ID`/secret in `deploy/secrets/` to actually work — without
  one it shows a clear "not configured" message instead of failing silently.
- **Cart**: add a product from its detail page or the catalog grid, adjust
  quantity, remove items — works as a guest (Redis-backed, survives a page
  reload via a cookie) or logged in (Postgres-backed, survives across
  sessions/devices). Log in with items already in a guest cart and they
  merge into your account cart automatically.
- **Checkout**: `/checkout` — single page, address form (pre-filled from your
  account if logged in), live order summary. With Stripe configured
  (`deploy/secrets/stripe_secret_key`), a real Stripe Elements payment step
  follows. Without Stripe configured, the order is still created — there's
  just no payment step to confirm.

### Stripe test cards

All of Stripe's own published test-mode numbers — no real card ever touches
this app (card entry lives entirely inside Stripe's own `PaymentElement`
iframe; the backend's `PaymentService` never receives or stores card data,
only a Stripe `PaymentIntent` id). Any future expiry date, any 3-digit CVC,
any postal code.

| Scenario | Card number | What you'll see |
| --- | --- | --- |
| Success | `4242 4242 4242 4242` | Payment confirms; order status becomes `PAID` within a couple of seconds (webhook → RabbitMQ → order consumer). |
| Insufficient funds | `4000 0000 0000 9995` | "Your card has insufficient funds." — order shows `PAYMENT_FAILED`, reserved stock is released. |
| Card expired | `4000 0000 0000 0069` | "Your card has expired." |
| Payment gateway timeout | `4000 0000 0000 0119` | "The payment gateway timed out. Please try again." (Stripe's `processing_error` decline). |
| Invalid card number | `4242 4242 4242 4241` | Rejected before it even reaches Stripe — this number fails the Luhn check, so Stripe.js blocks submission client-side with "Your card number is invalid." |
| Generic decline | `4000 0000 0000 0002` | A plain "card declined," for comparison against the specific scenarios above. |

Each of the first four maps to a distinct message via `StripeWebhookController`'s
own decline-code classification (`classifyFailure`) — not one generic bucket —
so testing all four is what actually demonstrates that requirement, not just
the success/decline pair.
- **Order emails**: each stage sends its own email (same Mailpit catcher as
  above). "Order received" goes out at checkout and says payment is still to
  come. "Payment received" follows once the Stripe webhook → RabbitMQ →
  order-consumer pipeline confirms payment, with the invoice PDF attached.
  "Payment failed" carries the decline reason. Shipped, delivered and refunded
  each send their own email when staff make that change.
- **Order history**: `/orders` — filter by status and date range, open any
  order for its full line-item + event-history detail. Cancel an unpaid
  order (releases its inventory reservation) or a paid one (issues a real
  Stripe refund) from the detail page.

### Bank-link test payments

Bank links are off unless switched on, so a fresh clone needs no Montonio
account. To try them against Montonio's sandbox:

1. From the Montonio Partner System, take the **sandbox** access key and
   secret key.
2. In `.env`, set `MONTONIO_ENABLED=true`,
   `MONTONIO_API_URL=https://sandbox-stargate.montonio.com/api`, and
   `MONTONIO_ACCESS_KEY` to the sandbox access key.
3. Put the sandbox secret key in `deploy/secrets/montonio_secret_key`
   (`./deploy/init-secrets.sh` creates the file empty), then run
   `docker compose up -d --force-recreate backend`. A changed secret is only
   picked up when the container is recreated.

Checkout then offers **Bank link** next to card payment. Choosing a bank sends
you to Montonio's sandbox bank page; paying or cancelling there brings you back
to `/checkout/bank-link/return`, which shows the result and settles the order.

## Feature walkthrough

What's built and how to try it. **In the browser:**

- **Register** at `/register` — real reCAPTCHA widget (or a graceful
      "not configured" state if no keys are set), then land logged-in.
- **Reload the page** mid-session — you stay logged in (silent refresh
      via the `HttpOnly` cookie). Open DevTools → Application → Local/Session
      Storage: no access token stored anywhere — it only ever lives in memory.
- **Log out** (Navbar) — then try going back; you're logged out for real.
- **Browse the catalog** — filter by category (try a parent category
      like *Electronics*, not just a leaf like *Phones*), brand, an
      attribute (e.g. *color*), price; sort by price/rating; type-ahead
      search; click through numbered pages.
- **Open a product** — full detail, images, metric+imperial dimensions,
      attributes.
- **"Continue with Google"** — completes a real login if real credentials
      are configured; otherwise shows a clear "not configured" message rather
      than failing silently.
- **Forgot/reset password** — `/forgot-password`, submit an email, land
      on the same confirmation regardless of whether it's a real account
      (enumeration-safe). With real SMTP configured, the emailed link at
      `/reset-password?token=...` actually works end to end.
- **2FA** — Account page → "Set up 2FA": scan the real QR code with any
      authenticator app, confirm the code, save the recovery codes. Log out
      and back in — the code is now required. Then Account page → "Disable":
      confirm with a current code, 2FA is off again.
- **Account page** — edit your display name; change your password
      (needs the current one) and confirm you're signed out everywhere
      afterward, including the tab you did it from.
- **Add to cart, adjust quantity, remove an item** — totals update live;
      log in with a non-empty guest cart and it merges into your account.
- **Checkout as a guest, then again logged in** — address form pre-fills
      when logged in; order summary matches what's actually in the cart.
- **Find your address** at checkout or in the account's address book (country
  EE): type part of an address ("Pikk 1", "Männiku talu") and pick a match from
  the Estonian address register to fill in street, city and postal code. Editing
  a field afterwards turns it back into a typed address, and typing the whole
  address by hand always works.
- **Pay, or trigger a specific decline** (needs Stripe configured) — see
      [Stripe test cards](#stripe-test-cards) above for the full set (success,
      insufficient funds, expired card, gateway timeout, invalid number).
      Every failure shows a scenario-specific message, not a generic one, and
      leaves nothing dangling — no order left in limbo, reserved stock released.
- **Cancel an unpaid order, then refund a paid one** from `/orders/{id}`
      — both show up immediately in that order's event history.
- **Filter order history** by status and by date range.
- **Administration** — sign in as an ADMIN or SUPPORT account with 2FA turned on
  (Account page first), then open `/admin`. Products, categories, orders,
  delivery options, review moderation and users are each a tab. From an
  order's detail you can mark it shipped and then delivered, refund a paid
  order (ADMIN only), see every invoice issued for it, download any of them,
  and issue a new one, which is emailed to the customer as a PDF. Earlier
  invoices are kept, never edited.
- **Product images and attributes** — open a product in `/admin/products` and
  Edit it. Images and filter attributes save on their own, separately from the
  product form, so editing the text cannot remove them.
- **Raise a product's price after ordering it** (`UPDATE products SET
      price = ...` directly in Postgres) — the already-placed order's line
      item is untouched; the price snapshot proves it.

**Needs a terminal (`curl`), no UI for these specific checks — but real and
testable:**

- **Refresh token single-use**: `POST /api/auth/refresh` twice with the
      *same* cookie — the second call is `401` and revokes the whole session
      family, not just that one token.
- **Rate limiting**: hammer `POST /api/auth/login` — a `429` shows up
      once you exceed the configured bucket. Same for `/auth/password/reset`,
      `/auth/verify-email`, `/checkout`, `/cart/items`, and `/products` —
      each has its own bucket, and checkout/cart are keyed by account too
      (not just IP) once logged in.
- **Concurrent checkout on the last unit**: fire N simultaneous checkout
      requests for a product with `on_hand = 1` — exactly one succeeds,
      `on_hand` never goes negative (`InventoryServiceTest`'s 20-thread test
      is the automated version of this).
- **Unexpected JSON field rejected**: POST any endpoint with an extra
      field the DTO doesn't declare (e.g. `"role":"ADMIN"`) — `400`, not
      silently ignored.

`scripts/smoke.sh` automates most of this checklist (plus a few more
edge cases — tampered tokens, cookie attributes, enumeration-safety) against
a running stack in one shot:

```bash
./scripts/smoke.sh   # needs the docker compose stack up, plus curl + jq
```

## Setup

Requires Docker + Docker Compose — that's it to *run* the stack, frontend
included (`docker compose up` now builds `frontend/dist` itself via the
`frontend-build` service in `docker-compose.yml`, which Caddy waits on
before starting). No local JDK/Maven/Node/Postgres/Redis/RabbitMQ install
needed. Local JDK/Maven is only for `mvn test` (uses Testcontainers); local
Node is only for the hot-reload dev server (`npm run dev`) or the Playwright
E2E suite — see [Local demo](#local-demo) and
[Running the critical-user-flow (E2E) tests](#running-the-critical-user-flow-e2e-tests).
[Stripe's CLI](https://docs.stripe.com/stripe-cli) (`stripe listen --forward-to
localhost:8080/api/stripe/webhook`) is an optional host tool for exercising
real webhook delivery locally instead of the Stripe dashboard's "send test
webhook" button — not required to run the app.

```bash
git clone <repo>
cd i-love-shopping3

# One-time per environment: generates deploy/secrets/* (gitignored).
# Paste real values into mail_password / google_client_secret / stripe_secret_key
# afterwards if you want outbound email, Google login, or real Stripe test-mode
# payments to actually work — all three degrade gracefully without them
# (see RecaptchaService/GoogleOAuthProperties/StripeProperties.isEnabled).
# A blank stripe_secret_key means checkout still creates real orders — there's
# just no payment step to confirm (CheckoutController only attaches a
# PaymentIntent when Stripe is actually configured).
./deploy/init-secrets.sh

cp .env.example .env   # ships ready-to-run local defaults — no edits needed

# Brings up Postgres, Redis, RabbitMQ, the backend, and Caddy (self-signed
# local TLS) in one command. See docker-compose.override.yml, loaded automatically —
# it maps Caddy to unprivileged ports (8088/8443 by default, see .env) and
# gives Postgres/Redis/the backend loopback-only port bindings for local
# convenience. The real Hetzner deploy runs
# `docker compose -f docker-compose.yml up -d`, which ignores this override
# file and .env's CADDY_HTTP_PORT/CADDY_HTTPS_PORT entirely — it always gets
# the real 80/443 and a real Let's Encrypt cert via deploy/Caddyfile.
docker compose up -d --build
```

Optional keys. Add each one only if you want that feature to work for real.
Each goes in `deploy/secrets/`, is read when the container starts, and is
picked up by `docker compose up -d --force-recreate backend` (a plain restart
does not reload secrets).

| File in `deploy/secrets/` | What it enables | Where it comes from |
|---|---|---|
| `mail_password` | Real outbound email | A Gmail App Password (not your account password). Set `MAIL_USER` in `.env` to the same address |
| `google_client_secret` | "Continue with Google" | Google Cloud Console OAuth client. Set `GOOGLE_CLIENT_ID` in `.env` |
| `recaptcha_secret` | reCAPTCHA on registration | Google reCAPTCHA v2/v3 secret key. The matching **site** key goes in `frontend/.env` as `VITE_RECAPTCHA_SITE_KEY` |
| `stripe_secret_key` | Card payments | Stripe test secret key, `sk_test_...`. The **publishable** key goes in `frontend/.env` as `VITE_STRIPE_PUBLISHABLE_KEY` |
| `stripe_webhook_secret` | Payment confirmation from Stripe | `stripe listen --forward-to localhost:8080/api/stripe/webhook` prints a `whsec_...` value |

Keep the secrets in `deploy/secrets/` only. They are gitignored, and nothing
should be pasted into `.env` or the compose file.

Once healthy:

- API directly: `http://localhost:8080/api/...`
- API through Caddy (self-signed HTTPS, matches how the real deploy
  terminates TLS): `https://localhost:8443/api/...` — curl needs `-k`, and
  your browser will show a one-time certificate warning. This is Caddy's
  *internal* CA (`tls internal` in `deploy/Caddyfile.local`), deliberately
  different from the production Caddyfile's real Let's Encrypt cert
  (`deploy/Caddyfile`) — both live in the repo on purpose: self-signed for
  local development, Let's Encrypt for the real deploy.
- Actuator health (`/actuator/health/**`) is reachable locally on
  `:8080` only — it's what Docker's own healthcheck polls from inside the
  container, and it stays out of Caddy's public routes (404) and out of
  everything else under `/actuator` (denied), both in prod and locally.

Tear down: `docker compose down` (add `-v` only if you intentionally want to
wipe the local Postgres/Redis volumes — Postgres will not pick up a changed
`db_password` secret against an existing volume, since it only applies that
value at first initialization).

## Running the tests

```bash
cd backend
mvn clean verify
```

Needs Docker running — every test extending `ee.shop.support.IntegrationTest`
uses Testcontainers to spin up **real** Postgres 16 and Redis, never H2 (see
[DECISIONS.md §10](DECISIONS.md) for why). Security-critical logic
is tested by actually attacking it under concurrency, not just a happy path —
e.g. `TokenRotationTest` (20 threads racing one refresh token),
`RecoveryCodeServiceTest`, `RateLimitServiceTest`.

## Running the frontend test suite

```bash
cd frontend
npm install
npm run test
```

Vitest + React Testing Library. Mocks at the `useAuth`/`apiFetch` boundary
rather than the network — real component behavior (form validation, error
display, conditional rendering) exercised directly, without needing a live
backend. Includes regression coverage for a real bug found during review:
`/verify-email` didn't exist as a route at all, so a real verification
email's link rendered a blank page.

## Running the critical-user-flow (E2E) tests

```bash
# 1. Backend stack running — see "Setup" above.
# 2. Frontend dev server — see "Local demo" above (the E2E runner also starts
#    one itself if it isn't already up, via playwright.config.ts's webServer).
cd frontend
npx playwright install chromium   # one-time, downloads a real browser
npm run test:e2e
```

Playwright, against a REAL backend (Postgres/Redis/RabbitMQ, not mocked) —
these hit the actual running stack, not a component-level mock, covering the
two flows that most need proving end to end: registration
(`e2e/registration.spec.ts`) and the full guest checkout including a real
Stripe test-mode payment when Stripe is configured
(`e2e/checkout.spec.ts`). The registration tests skip themselves with a
clear reason if a real (non-test) reCAPTCHA site key is configured in this
environment's `frontend/.env` — a real checkbox challenge can't be completed
by an automated browser by design; a fresh clone's blank default has no
widget at all, so the tests run there unmodified.

## Performance analysis report

Full report, with every measured figure and the scenarios that produced it:
**[PERFORMANCE.md](../PERFORMANCE.md)**.

Headline results, measured on 2026-09-08 against the full Docker stack with
[k6](https://k6.io) (4 CPU cores, 14.5 GiB RAM):

| Objective | Target | Measured | |
|---|---|---|---|
| 90% of requests | < 2 s | **5.04 ms** at 50 concurrent users | ✅ |
| Concurrent users | ≥ 50 | **50** at 5 ms p90; degrades gracefully to 2000 | ✅ |
| Throughput | ≥ 10 tx/s | **430–884 req/s** | ✅ |
| Successful transactions | ≥ 98% | **100%** | ✅ |
| Error rate | < 5% | **0.00%** | ✅ |

- **Maximum concurrent users before responses exceed 5 s: ~1,400.** p95 crosses
  5 s between 1,000 users (3.78 s) and 1,500 (5.44 s).
- **Sustained throughput: ~430 req/s**, flat from 250 users upward — the
  signature of a saturated resource, where extra users buy queueing rather than
  work.
- **Peak resource use at 1,000 users:** backend 223% CPU (of 400% available),
  671 MiB RAM; PostgreSQL 50% CPU, 75 MiB. Memory never exceeded 5% of the host
  and showed no upward trend, so there is no leak under sustained load.
- **Main bottleneck:** the HikariCP pool is at its default of 10 connections,
  which is the likeliest cause of throughput falling from 884 to 430 req/s as
  concurrency rises. Raising `DB_POOL_MAX` needs no code change. The database
  itself is *not* a bottleneck — it used a fraction of the backend's CPU.

Four scenarios live in [`scripts/load/`](../scripts/load): catalogue browsing,
search with per-keystroke type-ahead, guest cart operations, and a saturation
ramp. The first three include think time, because a test without it measures
how fast a script can hammer a server rather than how the server behaves with
real users.

> Load testing needs `docker-compose.loadtest.yml`, which raises the per-IP
> rate limits — a load generator on one machine is a single IP and would
> otherwise measure the rate limiter instead of the application. **Rate-limit
> buckets are persisted in Redis and keep the capacity they were created with,
> so the overlay does nothing until Redis is flushed.** Both commands are in
> PERFORMANCE.md.

## Encryption at rest

Customer personal data — account email addresses and display names, saved
delivery addresses, and the name, email, phone and postal address snapshotted
onto every order — is stored as AES-256-GCM ciphertext. A database dump shows `enc:v1:0w2VbPxWrz...`, not an
address.

Encryption is applied by a JPA `AttributeConverter`, so it holds on every path
into the table rather than only the paths someone remembered to encrypt.
Ciphertext carries an `enc:v1:` prefix, which makes any row still in clear text
findable with a plain SQL `LIKE`. Partial indexes on the name columns of
`orders` and `users` keep that audit query cheap as the tables grow.

The email column needed more than a converter. Login has to find an account
**by** it and a unique index has to enforce one live account per address, but
AES-GCM uses a random IV — the same address encrypts differently every time, so
the column cannot be searched or uniquely indexed. `V24__email_blind_index.sql`
adds a **blind index**: the ciphertext for reading, and a deterministic keyed
HMAC beside it (`EmailHasher`) that every lookup and the uniqueness constraint
use. It is an HMAC rather than a bare SHA-256 because an unkeyed hash of an
email is trivially reversed — the search space is small enough that whoever has
the dump can just hash their own address list and match.

Already protected by something stronger, and therefore deliberately not
encrypted: passwords (Argon2id), recovery codes (Argon2id), refresh tokens
(SHA-256). A hash beats encryption where the value never needs reading back.
TOTP secrets are encrypted separately, by `TotpSecretCipher`, because the
application has to read them back to check a code.

Order **amounts** are deliberately left in clear: `NUMERIC(12,2) CHECK (>= 0)`
is a guarantee the database enforces on every writer, and storing base64 in a
TEXT column would downgrade it to a promise the application makes about itself
— while also moving every revenue sum out of SQL and into an application scan.
A total identifies nobody on its own; the parts of an order that identify
someone are encrypted. `EncryptionAtRestTest.orderAmountsStayNumericAndConstrained`
holds that line so it stays a decision rather than drifting into a gap.

> **Losing `app.security.field.key` is unrecoverable, and orders are retained
> seven years under the Estonian Accounting Act.** Back the key up separately
> from the database: a database backup alone is no longer a backup of the shop.
> The application refuses to boot without the key rather than silently running
> unencrypted.

## Search engines and link previews

The shop is a single-page React app, so its built `index.html` on its own is an
empty `<div id="root">` with one site-wide title. That is all a crawler or a link
preview that does not run JavaScript would see, for every URL.

The public pages (`/`, `/products`, `/products/{slug}`, `/about`, `/contacts`,
`/privacy` and `/sales-conditions`) are therefore routed by Caddy to the backend
(`SeoPageController`), which returns that same built `index.html` with the page
filled in from the live database:

- its own `<title>`, meta description and canonical URL, plus Open Graph tags (a
  product page also gets `og:image`);
- schema.org structured data: `Product` with price, availability, brand and
  rating, and a `BreadcrumbList`;
- readable content with real links (headings, product text, related products)
  inside `#root`, which React replaces as it starts.

A browser gets the same app either way. Pages are filled in per request rather
than prerendered at build time, because a prerendered product page would be wrong
the moment staff changed a price. An unknown or removed product answers a real
`404` marked `noindex`, and search results are `noindex` too. If the backend
cannot read the build (`503`) or cannot be reached (`502`/`504`), Caddy serves the
plain static app instead, so a backend problem never blanks the front page.

Titles and descriptions are written identically on the server and in the React
pages (`PageMeta`), so a crawler that runs JavaScript and one that does not see the
same values. To see what a crawler receives:

```bash
curl -sk https://localhost:8443/products/<slug> | head -40
```

## Usage: key endpoints

All under `/api`. See `ee.shop.identity.web` / `ee.shop.catalog.web` for the
full controllers.

**Full OpenAPI documentation** (generated from this code, not hand-maintained
— see `springdoc-openapi-starter-webmvc-ui` in `backend/pom.xml`):
- Interactive Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Raw OpenAPI 3 JSON: `http://localhost:8080/v3/api-docs`

Frontend-side OpenAPI codegen is still an open item (see `frontend/src/api/types.ts`)
— the DTOs there are still hand-mirrored from the backend for this minimal pass.

| Endpoint | Notes |
|---|---|
| `POST /auth/register` | reCAPTCHA-gated (degrades to disabled+warn without a configured secret); issues a token pair immediately |
| `POST /auth/login` | Requires TOTP code or a recovery code once 2FA is enabled on the account |
| `POST /auth/refresh` | Reads the `refresh_token` `HttpOnly` cookie; requires CSRF (`X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie); rotates on every call; reusing an already-rotated token revokes the entire session family |
| `POST /auth/logout` | Revokes the refresh family and denylists the current access token's `jti` in Redis for immediate effect |
| `GET /auth/oauth2/google/authorize` / `POST /auth/oauth2/google/callback` | Custom authorization-code flow — never trusts Google's own tokens past the callback exchange |
| `POST /auth/totp/setup` / `POST /auth/totp/verify` | Returns an `otpauth://` URI + QR-able secret, then 10 single-use recovery codes on confirmation |
| `POST /auth/totp/disable` | Requires a current TOTP/recovery code, not just an active session — a stolen access token alone can't strip 2FA off an account. Clears recovery codes too |
| `POST /auth/password/forgot` / `POST /auth/password/reset` | Identical response whether or not the email exists (enumeration-safe); reset revokes every existing session |
| `POST /auth/password/change` | Authenticated counterpart to `/reset` — requires the current password instead of an emailed token; also revokes every session, including the caller's |
| `POST /auth/profile` | Updates the display name; returns the updated `/auth/me` shape |
| `GET /products` | Faceted search — `category`, `brand`, `minPrice`/`maxPrice`, `attr_*`, `sort`, keyset `cursor`, `limit` |
| `GET /products/facets` | Available brands + attribute values for a category (or the whole catalog with none given) — what the filter dropdowns are actually built from |
| `GET /products/suggest` | Trigram (`pg_trgm`) type-ahead on product name |
| `GET /products/{slug}` | Full detail, dimensions in both metric and imperial |
| `GET /categories` | Category tree |
| `POST /admin/products/{productId}/images` | Admin-only image upload (JPEG/PNG/WebP, 5MB limit). The bytes are decoded to verify they really are an image, and the file is stored under a random name. Also `GET` (list) and `DELETE /admin/products/{productId}/images/{imageId}` |
| `GET /cart` / `POST /cart/items` / `PATCH /cart/items/{productId}` / `DELETE /cart/items/{productId}` / `DELETE /cart` | Guest (cookie-identified) or logged-in (Bearer token) — resolved server-side, same endpoints either way. Mutations require the CSRF header (ambient guest-cookie identity, same reasoning as `/auth/refresh`) |
| `POST /checkout` | Requires an `Idempotency-Key` header — a retry with the same key returns the same order rather than creating a second one. Line items always come from the caller's own server-side cart, never the request body. Attaches a Stripe `paymentClientSecret` to the response when Stripe is configured |
| `GET /addresses/search?q=` | Public and rate limited. Searches the Estonian address register (Maa- ja Ruumiamet's In-AKS service) through this server, so the customer's browser never contacts it. Returns form-ready fields plus the register id (`adsOid`). `POST /checkout` and the address book re-check a submitted `adsOid` against the register and reject fields that do not match it |
| `POST /stripe/webhook` | Stripe calls this directly (signature-verified via `Stripe-Signature`, never trusted otherwise) — publishes a payment result to RabbitMQ, which a consumer folds into `order_events` and converts/releases the matching inventory reservation |
| `GET /orders` | The caller's own orders only — `status` and `from`/`to` (ISO date-time) filters, paginated |
| `GET /orders/last-address` | Billing name/phone/address from the caller's most recent order, for pre-filling a new checkout — `204` if they have none |
| `GET /orders/{id}` | Full detail incl. event history — `404`, not `403`, for another user's order |
| `POST /orders/{id}/cancel` | An unpaid order releases its inventory reservation; an already-paid order gets a real Stripe refund instead — both append the corresponding `order_events` row |

### Further endpoints

Every route below is also in the generated OpenAPI document. Admin routes need
an ADMIN or SUPPORT account with 2FA turned on (`@StaffOnly`), or an ADMIN account
with 2FA turned on for the actions marked `@AdminOnly`.

| Endpoint | Access | Notes |
|---|---|---|
| `GET /auth/me` | signed in | Current user, role and whether 2FA is on |
| `POST /auth/verify-email` | public | Consumes the emailed one-time token |
| `GET /home` | public | Featured products and promoted collections, one call |
| `GET /delivery-options` | public | Active delivery methods, their price and lead time |
| `POST /contact` | public | Contact form. Rate limited, and a failed send returns `503` rather than pretending it worked |
| `GET /products/{slug}/related` | public | Other products from the same category |
| `GET /products/{productId}/reviews` | public | `sort` = `NEWEST`, `HELPFUL`, `RATING_HIGH` or `RATING_LOW` |
| `GET /products/{productId}/reviews/eligibility` | signed in | Whether the caller has a PAID or later order for this product |
| `POST /products/{productId}/reviews` | verified purchaser | One review per user per product. Rejected with `403` unless the purchase check passes |
| `PATCH` / `DELETE /reviews/{id}` | author | Edit or remove your own review |
| `POST` / `DELETE /reviews/{id}/helpful` | signed in | Helpful vote. Idempotent, so a double click counts once |
| `GET` / `POST /account/addresses` | signed in | Saved delivery addresses |
| `PUT` / `DELETE /account/addresses/{id}` | signed in | Only your own addresses. Another user's id returns `404` |
| `GET /account/data` | signed in | GDPR data export (Art. 15/20) |
| `DELETE /account` | signed in | GDPR erasure (Art. 17). Needs the current password. Orders are kept for 7 years and personal data is scrubbed |
| `GET /admin/products` | ADMIN | All products, including removed ones (`includeDeleted=true`) |
| `POST` / `PUT` / `DELETE /admin/products[/{id}]` | ADMIN | Product CRUD. Delete is a soft delete |
| `POST /admin/products/{id}/restore` | ADMIN | Brings back a removed product |
| `POST /admin/products/bulk` / `POST /admin/products/bulk/csv` | ADMIN | Bulk import. All or nothing: one bad row and nothing is saved |
| `GET` / `PUT /admin/products/{id}/attributes` | ADMIN | Filter attributes. PUT replaces the whole set |
| `POST` / `PUT` / `DELETE /admin/categories[/{id}]` | ADMIN | Category CRUD |
| `GET` / `POST` / `PUT` / `DELETE /admin/delivery-options[/{id}]` | ADMIN | Delivery method CRUD, including inactive methods. The public list is `GET /delivery-options` |
| `GET /admin/orders` / `GET /admin/orders/{id}` | staff | List and detail, with the customer's name and email |
| `POST /admin/orders/{id}/shipped` | staff | Only a PAID order can be shipped |
| `POST /admin/orders/{id}/delivered` | staff | Only a SHIPPED order can be delivered |
| `POST /admin/orders/{id}/refund` | ADMIN | Real Stripe refund. PAID, SHIPPED or DELIVERED orders only |
| `GET /admin/orders/{id}/billing-documents` | staff | Invoices issued for an order, with who issued each |
| `GET /admin/orders/{id}/billing-documents/{documentId}/download` | staff | Downloads the invoice PDF |
| `POST /admin/orders/{id}/billing-documents/reissue` | ADMIN | Issues a new invoice and emails it. Earlier ones are kept |
| `GET /admin/users` / `GET /admin/users/{id}` | staff | Accounts |
| `PUT /admin/users/{id}/role` / `PUT /admin/users/{id}/disabled` | ADMIN | Role changes and disabling. You cannot change your own role |
| `GET /admin/reviews` / `PATCH /admin/reviews/{id}` | staff | Review moderation. Hiding a review drops it from the product's rating |

Register/login/refresh/forgot-password/verify-email/password-reset are all
rate-limited per IP; checkout/cart/product-search are rate-limited per IP
**and** per account once logged in (Bucket4j + Redis, token-bucket, see
`RateLimitService` / `app.rate-limit` in `application.yml`) — hammering any
of them past its configured capacity returns `429`.

## Database notes

`delivery_options` has no foreign key from `orders` on purpose — like
`products`, an order snapshots the delivery method's name/price/lead time at
purchase time (`orders.delivery_*`), so retiring or re-pricing a delivery
option later cannot rewrite what an existing order says it paid for shipping.

Notes that don't fit in the diagram:

- `refresh_tokens.parent_id` is how the family reuse-detection chain works —
  see [DECISIONS.md §6](DECISIONS.md).
- `audit_log` has no `updated_at`/soft-delete: the app's DB role
  (`shop_runtime`) can only `INSERT`/`SELECT` on it, enforced at the Postgres
  privilege level, not just in application code — see
  [DECISIONS.md §8](DECISIONS.md).
- `billing_documents` is append-only the same way: no `UPDATE`/`DELETE`/
  `TRUNCATE` privilege for the app role, backed by a trigger. A corrected
  invoice is a new row, never an edit of an old one.
- `products.price`/`currency` and the physical dimension columns are what
  `order_lines` snapshots at purchase time rather than joining to at
  order-read time — a later price change on `products` never touches an
  existing order — see [DECISIONS.md §2](DECISIONS.md).
- Money is `NUMERIC(12,2)`, never `float`/`double` — see
  [DECISIONS.md §4](DECISIONS.md).
- There is no mutable `orders.status` column. Current status is always
  DERIVED by folding `order_events` — see [DECISIONS.md §1](DECISIONS.md).
  `INVOICE_ISSUED` is the one event type excluded from that fold: it records
  that a billing document was (re)issued, not a change in the order's status.
- `inventory` (`on_hand`/`reserved`) is the source of truth for "can this be
  sold," never `products.stock_quantity` (display-only). A reservation is
  active while both `released_at` and `sold_at` are `NULL`; the checkout
  reserves, a successful payment converts it to `sold_at`, a failure/
  cancellation/expiry sets `released_at` — see [DECISIONS.md §3](DECISIONS.md).
- Guest carts live entirely in Redis, keyed by an opaque cookie id — there is
  deliberately no guest-cart table in Postgres; a cart only earns a row here
  once it belongs to a real account (merged in on login).
- `processed_stripe_events` is the idempotency claim for Stripe's
  at-least-once webhook delivery — `event_id` as the primary key means a
  replayed webhook hits a real unique-constraint violation on a duplicate
  `INSERT`, not a second-order check.
- `users.email` is ciphertext (AES-256-GCM); `email_hash` is a separate keyed
  HMAC used as the actual lookup/uniqueness key, since you cannot index
  encrypted text for equality search. `reviews_one_per_user_per_product` and
  `user_addresses_one_default_per_user` are partial/composite unique indexes,
  not visible as a plain column constraint in the diagram above.

### Column and relationship notes

The diagram omits these notes to keep it narrow:

- `users.email`: ciphertext — see email_hash below
- `users.email_hash`: keyed HMAC, the actual lookup key
- `users.role`: CUSTOMER / SUPPORT / ADMIN
- `refresh_tokens.token_sha256`: UNIQUE
- `one_time_tokens.token_sha256`: UNIQUE
- `categories.slug`: UNIQUE
- `categories.featured`: promoted as a home-page collection
- `products.slug`: UNIQUE among non-deleted rows
- `products.avg_rating`: denormalized, maintained by the reviews trigger
- `products.review_count`: denormalized, maintained by the reviews trigger
- `products.deleted_at`: soft delete — order_lines must survive
- `product_images.storage_key`: original, as uploaded
- `product_images.thumbnail_key`: nullable — ~400px rendition
- `product_images.medium_key`: nullable — ~1000px rendition
- `orders.idempotency_key`: UNIQUE
- `processed_bank_link_results` does the same for Montonio, whose result arrives
  twice by design: its notification, retried for up to two days, and the
  customer's return from the bank. The key is the Montonio order and the
  status, so the one result a bank can change later (voiding a payment it
  already confirmed) is still seen, once.
- `bank_link_refunds.outcome` is written once, when Montonio settles an
  attempt, by an `UPDATE ... WHERE outcome IS NULL` whose row count decides who
  records it. A partial unique index allows one open attempt per order, so
  concurrent refund requests share one idempotency key instead of sending two
  refunds. The order's own history is still only appended to `order_events`.
- `orders.billing_name`: encrypted
- `orders.billing_email`: encrypted
- `orders.billing_phone`: encrypted, nullable
- `orders.billing_address_line1`: encrypted
- `orders.billing_address_line2`: encrypted, nullable
- `orders.billing_city`: encrypted
- `orders.billing_postal_code`: encrypted
- `orders.billing_country`: encrypted
- `orders.delivery_code`: nullable — snapshotted, not an FK
- `orders.delivery_name`: nullable, snapshot
- `orders.delivery_price`: nullable, snapshot
- `orders.delivery_lead_days_min`: nullable, snapshot
- `orders.delivery_lead_days_max`: nullable, snapshot
- `order_lines.product_name`: snapshot
- `order_lines.unit_price`: snapshot, gross
- `order_lines.vat_rate`: snapshot
- `order_events.event_type`: CREATED / PAID / PAYMENT_FAILED / CANCELLED / SHIPPED / DELIVERED / REFUNDED / INVOICE_ISSUED
- `payments.stripe_payment_intent_id`: UNIQUE
- `billing_documents.storage_key`: generated invoice PDF
- `delivery_options.code`: UNIQUE
- `user_addresses.is_default`: at most one per user
- `reviews.rating`: 1–5
- `reviews.status`: VISIBLE / HIDDEN — moderation
- `reviews.helpful_count`: denormalized, maintained by a trigger

- `USERS ||--o{ REFRESH_TOKENS`: owns
- `USERS ||--o{ ONE_TIME_TOKENS`: owns
- `USERS ||--o{ OAUTH_IDENTITIES`: links
- `USERS ||--o{ RECOVERY_CODES`: owns
- `USERS ||--o{ AUDIT_LOG`: acts as (nullable)
- `USERS ||--o| CARTS`: has (logged-in only)
- `USERS ||--o{ ORDERS`: places (nullable — guest checkout)
- `USERS ||--o{ USER_ADDRESSES`: saves
- `USERS ||--o{ REVIEWS`: writes
- `USERS ||--o{ REVIEW_VOTES`: marks helpful
- `USERS ||--o{ BILLING_DOCUMENTS`: issues (nullable — system-issued at payment time)
- `REFRESH_TOKENS ||--o{ REFRESH_TOKENS`: parent (rotation chain)
- `CATEGORIES ||--o{ CATEGORIES`: parent (self-referential)
- `CATEGORIES ||--o{ PRODUCTS`: contains
- `PRODUCTS ||--o{ PRODUCT_IMAGES`: has
- `PRODUCTS ||--o{ PRODUCT_ATTRIBUTES`: has (EAV facets)
- `PRODUCTS ||--o| INVENTORY`: tracked by
- `PRODUCTS ||--o{ RESERVATIONS`: held against
- `PRODUCTS ||--o{ CART_ITEMS`: in
- `PRODUCTS ||--o{ REVIEWS`: reviewed by
- `CARTS ||--o{ CART_ITEMS`: contains
- `ORDERS ||--o{ ORDER_LINES`: snapshots
- `ORDERS ||--o{ ORDER_EVENTS`: derives status from
- `ORDERS ||--o{ PAYMENTS`: links to Stripe PaymentIntent
- `ORDERS ||--o{ BILLING_DOCUMENTS`: invoiced by
- `RESERVATIONS ||--o| ORDER_LINES`: backs
- `REVIEWS ||--o{ REVIEW_VOTES`: marked helpful by
