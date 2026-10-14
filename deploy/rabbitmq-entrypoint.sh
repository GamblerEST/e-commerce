#!/bin/sh
# The official rabbitmq image's docker-entrypoint.sh dropped support for
# *_FILE env vars (RABBITMQ_DEFAULT_PASS_FILE / RABBITMQ_DEFAULT_USER_FILE) —
# it now hard-fails with "deprecated" on both. RABBITMQ_DEFAULT_PASS itself
# (no _FILE) still works, so this wrapper reads the Docker secret file at
# container-internal startup and exports it as that plain var right before
# handing off to the real entrypoint. The raw password never appears in the
# Compose file or in `docker inspect` (which only shows the declared
# environment, not what a script exports at runtime) — the project's secrets
# discipline, worked around the one place upstream stopped supporting it
# directly.
set -eu

RABBITMQ_DEFAULT_USER="${RABBITMQ_DEFAULT_USER:-shop}"
export RABBITMQ_DEFAULT_USER
export RABBITMQ_DEFAULT_PASS="$(cat /run/secrets/rabbitmq_password)"

exec docker-entrypoint.sh "$@"
