#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# start-ranger.sh — Bring up the Ranger stack and wait until it is healthy.
#
# Usage:
#   ./start-ranger.sh [--timeout <seconds>] [--interval <seconds>] [--compose-file <path>]
#
# Defaults:
#   --timeout       120   Maximum seconds to wait for Ranger Admin readiness
#   --interval        5   Seconds between health-check polls
#   --compose-file  ../docker/docker-compose.yml
# ---------------------------------------------------------------------------
set -e

# ── Defaults ────────────────────────────────────────────────────────────────
TIMEOUT=120
INTERVAL=5
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker/docker-compose.yml"
HEALTH_URL="http://localhost:6080/login.jsp"

# ── Argument parsing ───────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --timeout)
      TIMEOUT="$2"
      shift 2
      ;;
    --interval)
      INTERVAL="$2"
      shift 2
      ;;
    --compose-file)
      COMPOSE_FILE="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

# ── Start the Ranger stack ─────────────────────────────────────────────────
echo "Starting Ranger stack using compose file: ${COMPOSE_FILE}"
docker compose -f "${COMPOSE_FILE}" up -d

# ── Poll for readiness ─────────────────────────────────────────────────────
elapsed=0
echo "Waiting for Ranger Admin to become ready at ${HEALTH_URL} (timeout=${TIMEOUT}s, interval=${INTERVAL}s)..."

while true; do
  if curl -sf "${HEALTH_URL}" > /dev/null 2>&1; then
    echo "Ranger Admin is ready at http://localhost:6080"
    exit 0
  fi

  elapsed=$((elapsed + INTERVAL))
  if [ "${elapsed}" -ge "${TIMEOUT}" ]; then
    echo "ERROR: Ranger Admin did not become ready within ${TIMEOUT} seconds." >&2
    echo "Dumping container logs:" >&2
    docker compose -f "${COMPOSE_FILE}" logs
    exit 1
  fi

  sleep "${INTERVAL}"
done
