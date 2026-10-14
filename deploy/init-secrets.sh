#!/usr/bin/env bash
# Generates the secret files that docker-compose mounts into containers.
# Run ONCE per environment. Never commit deploy/secrets/ — it is gitignored.
#
#   ./deploy/init-secrets.sh
#
# Secrets you must fill in by hand afterwards (they come from third parties):
#   deploy/secrets/mail_password
#   deploy/secrets/google_client_secret
#   deploy/secrets/recaptcha_secret
#   deploy/secrets/stripe_secret_key

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/secrets"
mkdir -p "$DIR"
chmod 700 "$DIR"

gen() {
  local name="$1" bytes="${2:-32}"
  local path="$DIR/$name"

  if [[ -f "$path" ]]; then
    echo "  = $name (exists, leaving alone)"
    return
  fi

  # openssl rand is a CSPRNG. Do NOT substitute $RANDOM, date, or a UUID —
  # they are predictable and a predictable JWT signing key means anyone can
  # mint an admin token.
  # `tr -d '\n'` matters: a trailing newline becomes part of the secret value
  # when Spring reads the file, and then the value in the container silently
  # differs from the one you think you set.
  openssl rand -base64 "$bytes" | tr -d '\n' > "$path"
  # 444, not 400: plain (non-Swarm) `docker compose` bind-mounts this file
  # as-is, preserving the HOST file's owner/mode — the container reads it as
  # a different, non-root UID (the Dockerfile's "app" user), which a
  # 400-owner-only file denies outright (AccessDeniedException at boot).
  # The real access boundary is this directory's own 700
  # (blocks other host users entirely), not the individual file mode.
  chmod 444 "$path"
  echo "  + $name ($bytes bytes)"
}

placeholder() {
  local name="$1"
  local path="$DIR/$name"
  if [[ -f "$path" ]]; then
    echo "  = $name (exists, leaving alone)"
    return
  fi
  printf '' > "$path"
  chmod 444 "$path"
  echo "  ! $name (EMPTY — paste the real value in)"
}

echo "Generating secrets in $DIR"
gen db_password 32
# Password for shop_runtime, the LEAST-PRIVILEGED role the application actually
# connects as (see R__runtime_role.sql). Separate from db_password, which is the
# owner's and is used only by Flyway for migrations.
gen db_runtime_password 32
gen redis_password 32
gen rabbitmq_password 32
gen jwt_secret 48          # 48 bytes = 384 bits, comfortably over HS256's 256-bit floor
gen totp_encryption_key 32 # AES-256 key, derived via SHA-256 of this — see TotpEncryptionProperties
# Encrypts customer PII (names, addresses, phone numbers) at rest.
# BACK THIS UP SEPARATELY FROM THE DATABASE. A database backup without this
# key restores ciphertext nobody can read, and orders are retained 7 years.
gen field_encryption_key 32 # AES-256 key — see FieldEncryptionProperties
placeholder mail_password
placeholder google_client_secret
placeholder recaptcha_secret
placeholder stripe_secret_key
placeholder stripe_webhook_secret
placeholder montonio_secret_key

echo
echo "Done. Now:"
echo "  1. Paste your SMTP password into        $DIR/mail_password"
echo "  2. Paste your Google OAuth secret into   $DIR/google_client_secret"
echo "  3. Paste your reCAPTCHA secret key into  $DIR/recaptcha_secret"
echo "     (the SITE key is not secret — it goes in frontend/.env, see .env.example)"
echo "  4. Paste your Stripe secret key into      $DIR/stripe_secret_key"
echo "     (the PUBLISHABLE key is not secret — it goes in frontend/.env too)"
echo "  5. Paste your Stripe webhook signing secret into $DIR/stripe_webhook_secret"
echo "     (from the Stripe dashboard's webhook endpoint, or \`stripe listen\` for local dev)"
echo "  6. Copy .env.example to .env and set DOMAIN, MAIL_HOST, etc."
echo
echo "Back these up somewhere safe and offline. Losing jwt_secret logs out"
echo "every user; losing db_password locks you out of your own database."
