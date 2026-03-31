#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-integration-tests.sh — Full lifecycle runner for Ranger integration tests.
#
# Provisions the Ranger stack, runs integration tests, and tears down.
#
# Usage:
#   ./run-integration-tests.sh [--skip-teardown]
#
# Options:
#   --skip-teardown   Keep the Ranger stack running after tests (useful for debugging)
#
# Exit code:
#   Returns the Maven test exit code (0 = tests passed, non-zero = tests failed).
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKIP_TEARDOWN=false
MVN_EXIT_CODE=0

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
    echo "Skipping teardown (--skip-teardown). The Ranger stack is still running."
    echo "  Ranger Admin: http://localhost:6080"
    echo "  To stop manually: ${SCRIPT_DIR}/stop-ranger.sh"
  else
    echo ""
    echo "Tearing down Ranger stack..."
    "${SCRIPT_DIR}/stop-ranger.sh"
  fi
}
trap cleanup EXIT

# ── Provision Ranger stack ─────────────────────────────────────────────────
echo "=== Provisioning Ranger stack ==="
"${SCRIPT_DIR}/start-ranger.sh"

# ── Run integration tests ──────────────────────────────────────────────────
echo ""
echo "=== Running integration tests ==="
mvn verify -Pintegration-test || MVN_EXIT_CODE=$?

# ── Report result ──────────────────────────────────────────────────────────
echo ""
if [ "$MVN_EXIT_CODE" -eq 0 ]; then
  echo "=== Integration tests PASSED ==="
else
  echo "=== Integration tests FAILED (exit code: ${MVN_EXIT_CODE}) ==="
fi

exit "$MVN_EXIT_CODE"
