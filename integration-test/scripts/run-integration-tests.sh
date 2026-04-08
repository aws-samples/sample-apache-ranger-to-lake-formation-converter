#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-integration-tests.sh — Full lifecycle runner for Ranger integration tests.
#
# Provisions the Ranger stack with two-phase startup (Ranger first, then
# conversion-server after servicedef + service instance are installed),
# runs integration tests, and tears down.
#
# Usage:
#   ./run-integration-tests.sh [--skip-teardown]
#
# Options:
#   --skip-teardown   Keep the stack running after tests (useful for debugging)
#
# Exit code:
#   Returns the Maven test exit code (0 = tests passed, non-zero = tests failed).
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker/docker-compose.yml"
DRY_RUN_OUTPUT_DIR="${SCRIPT_DIR}/../docker/dry-run-output"
SKIP_TEARDOWN=false
MVN_EXIT_CODE=0

RANGER_HEALTH_URL="http://localhost:6080/login.jsp"
RANGER_HEALTH_TIMEOUT=120
RANGER_HEALTH_INTERVAL=5

CONVERSION_SERVER_HEALTH_TIMEOUT=120
CONVERSION_SERVER_HEALTH_INTERVAL=5

# ── Argument parsing ───────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-teardown)
      SKIP_TEARDOWN=true
      shift
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--skip-teardown]" >&2
      exit 1
      ;;
  esac
done

# ── Check Docker is available ──────────────────────────────────────────────
if ! command -v docker &> /dev/null; then
  echo "ERROR: Docker is not installed or not in PATH." >&2
  echo "Please install Docker: https://docs.docker.com/get-docker/" >&2
  exit 1
fi

if ! docker info &> /dev/null; then
  echo "ERROR: Docker daemon is not running." >&2
  echo "Please start Docker and try again." >&2
  exit 1
fi

# ── Teardown handler (runs even if tests fail) ─────────────────────────────
cleanup() {
  if [ "$SKIP_TEARDOWN" = true ]; then
    echo ""
    echo "Skipping teardown (--skip-teardown). The stack is still running."
    echo "  Ranger Admin: http://localhost:6080"
    echo "  To stop manually: docker compose -f ${COMPOSE_FILE} down -v"
  else
    echo ""
    echo "Tearing down stack..."
    docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans || true
    if [ -d "${DRY_RUN_OUTPUT_DIR}" ]; then
      echo "Cleaning dry-run output directory: ${DRY_RUN_OUTPUT_DIR}"
      rm -rf "${DRY_RUN_OUTPUT_DIR:?}"/*  2>/dev/null || true
    fi
    echo "Teardown complete."
  fi
}
trap cleanup EXIT

# ── Clean state from previous runs ────────────────────────────────────────
echo "=== Cleaning up previous runs ==="
docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans 2>/dev/null || true

# ── Build conversion-server image ─────────────────────────────────────────
echo ""
echo "=== Building conversion-server Docker image ==="
if ! docker compose -f "${COMPOSE_FILE}" build conversion-server; then
  echo "ERROR: Failed to build conversion-server image." >&2
  exit 1
fi

# ── Start Ranger stack (Phase 1: ranger-db, ranger-solr, ranger-admin) ────
echo ""
echo "=== Starting Ranger stack (ranger-db, ranger-solr, ranger-admin) ==="
docker compose -f "${COMPOSE_FILE}" up -d ranger-db ranger-solr ranger-admin

# ── Wait for Ranger Admin health ──────────────────────────────────────────
elapsed=0
echo "Waiting for Ranger Admin to become ready at ${RANGER_HEALTH_URL} (timeout=${RANGER_HEALTH_TIMEOUT}s)..."

while true; do
  if curl -sf "${RANGER_HEALTH_URL}" > /dev/null 2>&1; then
    echo "Ranger Admin is ready at http://localhost:6080"
    break
  fi

  elapsed=$((elapsed + RANGER_HEALTH_INTERVAL))
  if [ "${elapsed}" -ge "${RANGER_HEALTH_TIMEOUT}" ]; then
    echo "ERROR: Ranger Admin did not become ready within ${RANGER_HEALTH_TIMEOUT} seconds." >&2
    echo "Dumping container logs:" >&2
    docker compose -f "${COMPOSE_FILE}" logs ranger-admin
    exit 1
  fi

  sleep "${RANGER_HEALTH_INTERVAL}"
done

# ── Install Lake Formation service definition ──────────────────────────────
echo ""
echo "=== Installing Lake Formation service definition ==="
"${SCRIPT_DIR}/install-servicedef.sh"

# ── Create service instance ────────────────────────────────────────────────
echo ""
echo "=== Creating lakeformation service instance ==="
"${SCRIPT_DIR}/create-service-instance.sh"

# ── Start conversion-server (Phase 2) ─────────────────────────────────────
echo ""
echo "=== Starting conversion-server ==="
docker compose -f "${COMPOSE_FILE}" up -d conversion-server

# ── Wait for conversion-server health ─────────────────────────────────────
elapsed=0
echo "Waiting for conversion-server to become healthy (timeout=${CONVERSION_SERVER_HEALTH_TIMEOUT}s)..."

while true; do
  # Check if the container is running and the Java process is alive
  CONTAINER_STATE=$(docker compose -f "${COMPOSE_FILE}" ps conversion-server --format json 2>/dev/null \
    | grep -o '"State":"[^"]*"' | head -1 | cut -d'"' -f4) || true

  if [ "${CONTAINER_STATE}" = "exited" ]; then
    echo "ERROR: conversion-server exited unexpectedly." >&2
    echo "Dumping conversion-server logs:" >&2
    docker compose -f "${COMPOSE_FILE}" logs conversion-server
    exit 1
  fi

  if [ "${CONTAINER_STATE}" = "running" ]; then
    # Verify the Java process is alive inside the container
    if docker exec docker-conversion-server-1 pgrep -f 'java.*ConversionServerMain' > /dev/null 2>&1; then
      echo "conversion-server is running and healthy."
      break
    fi
  fi

  elapsed=$((elapsed + CONVERSION_SERVER_HEALTH_INTERVAL))
  if [ "${elapsed}" -ge "${CONVERSION_SERVER_HEALTH_TIMEOUT}" ]; then
    echo "ERROR: conversion-server did not become healthy within ${CONVERSION_SERVER_HEALTH_TIMEOUT} seconds." >&2
    echo "Dumping conversion-server logs:" >&2
    docker compose -f "${COMPOSE_FILE}" logs conversion-server
    exit 1
  fi

  sleep "${CONVERSION_SERVER_HEALTH_INTERVAL}"
done

# ── Run integration tests ──────────────────────────────────────────────────
echo ""
echo "=== Running integration tests ==="
mvn verify -Pintegration-test -Ddry.run.output.path="${DRY_RUN_OUTPUT_DIR}" || MVN_EXIT_CODE=$?

# ── Report result ──────────────────────────────────────────────────────────
echo ""
if [ "$MVN_EXIT_CODE" -eq 0 ]; then
  echo "=== Integration tests PASSED ==="
else
  echo "=== Integration tests FAILED (exit code: ${MVN_EXIT_CODE}) ==="
fi

exit "$MVN_EXIT_CODE"
