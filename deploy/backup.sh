#!/usr/bin/env bash
# Nightly Postgres backup, encrypted, shipped off-box.
#
# Install on the Hetzner host:
#   sudo cp deploy/backup.sh /usr/local/bin/shop-backup
#   sudo chmod +x /usr/local/bin/shop-backup
#   (crontab) 15 3 * * * /usr/local/bin/shop-backup >> /var/log/shop-backup.log 2>&1
#
# ── Why not just use Hetzner snapshots? ──────────────────────────────────────
# A Hetzner snapshot images the whole disk while Postgres is mid-write. Postgres
# can usually recover from that (it is crash-safe), but "usually" is doing a lot
# of work in a sentence about your customers' orders. pg_dump takes a consistent
# logical snapshot inside a transaction — it is the only thing that is actually
# guaranteed to restore.
#
# Keep BOTH. Snapshots restore the machine fast; dumps restore the data
# correctly. They solve different problems.

set -euo pipefail

BACKUP_DIR=/var/backups/shop
RETAIN_DAYS=30
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP="$BACKUP_DIR/shop-$STAMP.dump"

mkdir -p "$BACKUP_DIR"

echo "[$(date -uIs)] starting backup"

# -Fc = custom format: compressed, and restorable table-by-table with pg_restore.
# Run inside the container so we do not need a postgres client on the host.
docker compose exec -T postgres \
  pg_dump -U shop -d shop -Fc > "$DUMP"

SIZE=$(stat -c %s "$DUMP")
if [[ "$SIZE" -lt 10000 ]]; then
  # A dump that is suspiciously tiny means pg_dump errored and we captured its
  # empty stdout. Fail loudly rather than cheerfully rotating away the last
  # good backup and replacing it with 200 bytes of nothing.
  echo "FATAL: dump is only ${SIZE} bytes — aborting" >&2
  rm -f "$DUMP"
  exit 1
fi

# ── Verify the backup actually restores ──────────────────────────────────────
# An untested backup is not a backup, it is a hope. This spins up a throwaway
# Postgres, restores into it, and checks the tables are really there. It adds
# ~20 seconds and it is the difference between having a disaster recovery plan
# and having a disaster.
echo "[$(date -uIs)] verifying restore"
VERIFY_CONTAINER="shop-restore-check-$$"
docker run -d --rm --name "$VERIFY_CONTAINER" \
  -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=verify \
  postgres:16-alpine > /dev/null

cleanup() { docker rm -f "$VERIFY_CONTAINER" > /dev/null 2>&1 || true; }
trap cleanup EXIT

for _ in $(seq 1 30); do
  docker exec "$VERIFY_CONTAINER" pg_isready -U postgres -q && break
  sleep 1
done

docker exec -i "$VERIFY_CONTAINER" pg_restore -U postgres -d verify --no-owner < "$DUMP"

TABLES=$(docker exec "$VERIFY_CONTAINER" psql -U postgres -d verify -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")

if [[ "$TABLES" -lt 5 ]]; then
  echo "FATAL: restored DB has only $TABLES tables — backup is not usable" >&2
  exit 1
fi
echo "[$(date -uIs)] restore verified ($TABLES tables)"

# ── Encrypt, then ship off-box ───────────────────────────────────────────────
# The dump contains every customer's name, email and address. Under GDPR it is
# personal data wherever it sits, including in a backup bucket. Encrypt it
# before it leaves this machine.
#
# age is a modern, hard-to-misuse alternative to GPG.
#   Setup once:  age-keygen -o /root/.age/backup.key
#                (store the PRIVATE key offline; you cannot restore without it)
if [[ -f /root/.age/backup.pub ]]; then
  age -R /root/.age/backup.pub -o "$DUMP.age" "$DUMP"
  rm -f "$DUMP"
  DUMP="$DUMP.age"
  echo "[$(date -uIs)] encrypted"
else
  echo "WARNING: no age key found — backup is UNENCRYPTED" >&2
fi

# Off-box copy. A backup on the same disk as the database protects you from
# 'DROP TABLE' and from nothing else. Hetzner Storage Box over rsync/SSH:
if [[ -n "${BACKUP_REMOTE:-}" ]]; then
  rsync -az --remove-source-files "$DUMP" "$BACKUP_REMOTE/"
  echo "[$(date -uIs)] shipped to $BACKUP_REMOTE"
else
  echo "WARNING: BACKUP_REMOTE unset — backup stayed on this machine" >&2
fi

find "$BACKUP_DIR" -name 'shop-*.dump*' -mtime +$RETAIN_DAYS -delete
echo "[$(date -uIs)] done"
