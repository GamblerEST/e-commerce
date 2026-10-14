#!/usr/bin/env bash
# End-to-end smoke test against a running
# `docker compose up -d --build` stack. Exits non-zero on ANY failure.
#
# Talks directly to the backend (localhost:8080), not through Caddy: the
# behaviour under test (cookie attributes, CSRF, rate limits, auth) lives in
# the backend, and going through Caddy locally would only add a self-signed
# TLS hop curl has to -k around for no extra coverage.
#
# Requires: the stack already up and the backend healthy
# (docker compose ps --> backend "healthy"), curl, jq, docker (for the one
# DB-side assertion — verification email row existing).

set -uo pipefail

BASE="${SMOKE_BASE_URL:-http://localhost:8080/api}"
PG_CONTAINER="${SMOKE_PG_CONTAINER:-$(cd "$(dirname "$0")/.." \&\& docker compose ps -q postgres 2>/dev/null)}"
FAIL=0
PASS=0

pass() { PASS=$((PASS+1)); echo "  PASS: $1"; }
fail() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

section() { echo; echo "=== $1 ==="; }

EMAIL="smoke-$(date +%s)-$$@example.test"
PASSWORD="Correcthorsebatterystaple9!"
COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

# ── 1. Register ──────────────────────────────────────────────────────────
# recaptchaToken: the local stack runs Google's OFFICIAL test secret key
# (deploy/secrets/recaptcha_secret), whose siteverify accepts any token —
# so this exercises the REAL verification code path (RecaptchaService does
# call Google) without needing a browser to solve a challenge. Production
# uses real keys; this script never runs there.
section "Register"
REGISTER_BODY=$(mktemp)
REGISTER_CODE=$(curl -s -o "$REGISTER_BODY" -w '%{http_code}' -c "$COOKIES" \
  -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Smoke Test\",\"recaptchaToken\":\"smoke-token\"}")

[ "$REGISTER_CODE" = "201" ] && pass "register returns 201" || fail "register returned $REGISTER_CODE (expected 201)"

VERIFY_ROW_COUNT=$(docker exec "$PG_CONTAINER" psql -U shop -d shop -t -A -c \
  "SELECT count(*) FROM one_time_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = '$EMAIL' AND t.purpose = 'EMAIL_VERIFY';" 2>/dev/null | tr -d '[:space:]')
[ "$VERIFY_ROW_COUNT" = "1" ] && pass "verification token row exists in one_time_tokens" || fail "expected exactly 1 verification token row, found '$VERIFY_ROW_COUNT'"
rm -f "$REGISTER_BODY"

# ── 2. Login: access token + refresh cookie attributes ─────────────────────
section "Login"
LOGIN_HEADERS=$(mktemp)
LOGIN_BODY=$(mktemp)
LOGIN_CODE=$(curl -s -D "$LOGIN_HEADERS" -o "$LOGIN_BODY" -w '%{http_code}' -c "$COOKIES" \
  -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")

[ "$LOGIN_CODE" = "200" ] && pass "login returns 200" || fail "login returned $LOGIN_CODE (expected 200)"

ACCESS_TOKEN=$(jq -r '.accessToken // empty' "$LOGIN_BODY")
[ -n "$ACCESS_TOKEN" ] && pass "login response carries an accessToken" || fail "login response has no accessToken"

REFRESH_SET_COOKIE=$(grep -i '^set-cookie: refresh_token=' "$LOGIN_HEADERS" || true)
if echo "$REFRESH_SET_COOKIE" | grep -qi 'HttpOnly' \
   && echo "$REFRESH_SET_COOKIE" | grep -qi 'Secure' \
   && echo "$REFRESH_SET_COOKIE" | grep -qi 'SameSite=Strict'; then
  pass "refresh_token cookie is HttpOnly + Secure + SameSite=Strict"
else
  fail "refresh_token cookie missing a required attribute: $REFRESH_SET_COOKIE"
fi
rm -f "$LOGIN_HEADERS" "$LOGIN_BODY"

# ── 3. Protected route: valid token → 200, tampered → 401 ─────────────────
section "Protected route"
ME_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/me" -H "Authorization: Bearer $ACCESS_TOKEN")
[ "$ME_CODE" = "200" ] && pass "/auth/me with valid access token returns 200" || fail "/auth/me with valid token returned $ME_CODE"

TAMPERED="${ACCESS_TOKEN}tampered"
TAMPERED_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/me" -H "Authorization: Bearer $TAMPERED")
[ "$TAMPERED_CODE" = "401" ] && pass "/auth/me with tampered token returns 401" || fail "/auth/me with tampered token returned $TAMPERED_CODE (expected 401)"

# ── 4. Refresh rotation + replay detection ─────────────────────────────────
section "Refresh rotation and replay detection"
OLD_REFRESH=$(grep 'refresh_token' "$COOKIES" | tail -1 | awk '{print $NF}')
XSRF_TOKEN=$(grep 'XSRF-TOKEN' "$COOKIES" | tail -1 | awk '{print $NF}')

REFRESH1_BODY=$(mktemp)
REFRESH1_CODE=$(curl -s -o "$REFRESH1_BODY" -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" \
  -X POST "$BASE/auth/refresh" -H "X-XSRF-TOKEN: $XSRF_TOKEN")
[ "$REFRESH1_CODE" = "200" ] && pass "refresh with valid cookie + CSRF header returns 200" || fail "refresh returned $REFRESH1_CODE (expected 200)"

NEW_ACCESS_TOKEN=$(jq -r '.accessToken // empty' "$REFRESH1_BODY")
[ -n "$NEW_ACCESS_TOKEN" ] && [ "$NEW_ACCESS_TOKEN" != "$ACCESS_TOKEN" ] && pass "refresh issues a NEW access token" || fail "refresh did not issue a new/different access token"
rm -f "$REFRESH1_BODY"

NEW_REFRESH=$(grep 'refresh_token' "$COOKIES" | tail -1 | awk '{print $NF}')
[ -n "$NEW_REFRESH" ] && [ "$NEW_REFRESH" != "$OLD_REFRESH" ] && pass "refresh rotates the refresh cookie to a new value" || fail "refresh cookie did not rotate"

# Replay the OLD (already-used) refresh token.
REPLAY_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  --cookie "refresh_token=$OLD_REFRESH; XSRF-TOKEN=$XSRF_TOKEN" \
  -X POST "$BASE/auth/refresh" -H "X-XSRF-TOKEN: $XSRF_TOKEN")
[ "$REPLAY_CODE" = "401" ] && pass "replaying the OLD refresh token returns 401" || fail "replaying old refresh token returned $REPLAY_CODE (expected 401)"

# The family must be dead now too — even the token that legitimately won the
# rotation above should no longer work (DECISIONS.md §6: replay revokes the
# WHOLE family, not just the stolen token).
FAMILY_DEAD_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  --cookie "refresh_token=$NEW_REFRESH; XSRF-TOKEN=$XSRF_TOKEN" \
  -X POST "$BASE/auth/refresh" -H "X-XSRF-TOKEN: $XSRF_TOKEN")
[ "$FAMILY_DEAD_CODE" = "401" ] && pass "post-replay, the rotated-in token is ALSO revoked (whole family dead)" || fail "rotated-in token still works after replay was detected (returned $FAMILY_DEAD_CODE) — family revocation is broken"

# ── 5. Password reset is email-enumeration-safe ────────────────────────────
section "Password reset enumeration safety"
KNOWN_BODY=$(mktemp); UNKNOWN_BODY=$(mktemp)
KNOWN_CODE=$(curl -s -o "$KNOWN_BODY" -w '%{http_code}' -X POST "$BASE/auth/password/forgot" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\"}")
UNKNOWN_CODE=$(curl -s -o "$UNKNOWN_BODY" -w '%{http_code}' -X POST "$BASE/auth/password/forgot" \
  -H 'Content-Type: application/json' -d '{"email":"definitely-not-registered-'"$$"'@example.test"}')

[ "$KNOWN_CODE" = "$UNKNOWN_CODE" ] && [ "$KNOWN_CODE" = "202" ] \
  && pass "known and unknown emails both return 202" || fail "known=$KNOWN_CODE unknown=$UNKNOWN_CODE (expected both 202)"
if diff -q "$KNOWN_BODY" "$UNKNOWN_BODY" >/dev/null; then
  pass "known and unknown emails return an identical response body"
else
  fail "response bodies differ between known and unknown email — enumeration leak"
fi
rm -f "$KNOWN_BODY" "$UNKNOWN_BODY"

# ── 6. Faceted search + suggestions ─────────────────────────────────────────
section "Catalog: faceted search"
SEARCH_BODY=$(mktemp)
SEARCH_CODE=$(curl -s -o "$SEARCH_BODY" -w '%{http_code}' "$BASE/products?sort=PRICE_ASC&limit=50")
[ "$SEARCH_CODE" = "200" ] && pass "GET /products returns 200" || fail "GET /products returned $SEARCH_CODE"

# Regression check for the numeric-vs-text sort bug (README/DECISIONS §4):
# 129.00 must sort before 1099.00. Sorting price as TEXT would reverse this.
PRICES_JSON=$(jq -c '[.items[].price]' "$SEARCH_BODY" 2>/dev/null)
IS_SORTED=$(jq -n --argjson p "$PRICES_JSON" '($p == ($p | sort_by(. + 0))) // false' 2>/dev/null)
if [ "$IS_SORTED" = "true" ]; then
  pass "PRICE_ASC results are numerically sorted ($(echo "$PRICES_JSON" | jq -c '.[0:4]'))"
else
  fail "PRICE_ASC results are NOT numerically sorted: $PRICES_JSON"
fi
rm -f "$SEARCH_BODY"

FILTERED_BODY=$(mktemp)
FILTERED_CODE=$(curl -s -o "$FILTERED_BODY" -w '%{http_code}' "$BASE/products?brand=Fieldstone&limit=50")
[ "$FILTERED_CODE" = "200" ] && pass "GET /products?brand=Fieldstone returns 200" || fail "brand filter returned $FILTERED_CODE"
ALL_FIELDSTONE=$(jq '[.items[].brand] | all(. == "Fieldstone")' "$FILTERED_BODY" 2>/dev/null)
NONEMPTY=$(jq '.items | length > 0' "$FILTERED_BODY" 2>/dev/null)
[ "$ALL_FIELDSTONE" = "true" ] && [ "$NONEMPTY" = "true" ] && pass "brand filter returns only matching, non-empty results" || fail "brand filter results wrong: $(cat "$FILTERED_BODY")"
rm -f "$FILTERED_BODY"

SUGGEST_BODY=$(mktemp)
SUGGEST_CODE=$(curl -s -o "$SUGGEST_BODY" -w '%{http_code}' "$BASE/products/suggest?q=Aurora")
[ "$SUGGEST_CODE" = "200" ] && pass "GET /products/suggest returns 200" || fail "suggest returned $SUGGEST_CODE"
SUGGEST_NONEMPTY=$(jq 'length > 0' "$SUGGEST_BODY" 2>/dev/null)
[ "$SUGGEST_NONEMPTY" = "true" ] && pass "suggest returns at least one result for 'Aurora'" || fail "suggest returned no results: $(cat "$SUGGEST_BODY")"
rm -f "$SUGGEST_BODY"

# ── 7. Rate limiting: hammer /login → at least one 429 ─────────────────────
# Deliberately LAST: this exhausts the real production login bucket (10/min,
# see application.yml) for this IP, so nothing after this step can rely on
# logging in again within the same minute.
section "Rate limiting (hammer /login)"
GOT_429=0
for i in $(seq 1 100); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"wrong-password\"}")
  if [ "$CODE" = "429" ]; then
    GOT_429=1
    break
  fi
done
[ "$GOT_429" = "1" ] && pass "hammering /login 100x triggers a 429 within the run" || fail "never got a 429 after 100 rapid /login attempts"

# ── Summary ──────────────────────────────────────────────────────────────
section "Summary"
echo "  $PASS passed, $FAIL failed"
if [ "$FAIL" -ne 0 ]; then
  echo "SMOKE TEST: FAIL"
  exit 1
fi
echo "SMOKE TEST: PASS"
