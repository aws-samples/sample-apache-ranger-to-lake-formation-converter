#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# stop-ranger.sh — Tear down the Ranger stack (idempotent).
#
# Usage:
#   ./stop-ranger.sh [--compose-file <path>]
#
# Defaults:
#   --compose-file  ../docker/docker-compose.yml
#
# Always exits 0 regardless of Docker Compose exit code so that CI pipelines
# are not blocked by cleanup failures.
# ---------------------------------------------------------------------------

# ── Defaults ────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker/docker-compose.yml"

# ── Argument parsing ───────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose-file)
      COMPOSE_FILE="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 0
      ;;
  esac
done

# ── Tear down the Ranger stack ─────────────────────────────────────────────
echo "Stopping Ranger stack using compose file: ${COMPOSE_FILE}"
docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans || true

echo "Ranger stack teardown complete."
exit 0
