#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# install-servicedef.sh — Install the Lake Formation service definition into Ranger Admin.
#
# Usage:
#   ./install-servicedef.sh [--ranger-url <url>] [--servicedef <path>]
#
# Defaults:
#   --ranger-url    http://localhost:6080
#   --servicedef    ../../conf/ranger-servicedef-lakeformation.json (relative to script dir)
#
# Checks if the servicedef exists first, then creates (POST) or updates (PUT).
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RANGER_URL="http://localhost:6080"
SERVICEDEF_FILE="${SCRIPT_DIR}/../../conf/ranger-servicedef-lakeformation.json"
AUTH="admin:rangerR0cks!"
API_PATH="/service/public/v2/api/servicedef"

# ── Argument parsing ───────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ranger-url)
      RANGER_URL="$2"
      shift 2
      ;;
    --servicedef)
      SERVICEDEF_FILE="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if [ ! -f "${SERVICEDEF_FILE}" ]; then
  echo "ERROR: Service definition file not found: ${SERVICEDEF_FILE}" >&2
  exit 1
fi

ENDPOINT="${RANGER_URL}${API_PATH}"
echo "Installing Lake Formation service definition into Ranger Admin at ${RANGER_URL}..."

# ── Check if servicedef already exists ─────────────────────────────────────
LOOKUP_CODE=$(curl -s -o /tmp/servicedef-lookup.json -w "%{http_code}" \
  -X GET "${ENDPOINT}/name/lakeformation" \
  -u "${AUTH}" 2>/dev/null) || true

if [ "${LOOKUP_CODE}" = "200" ]; then
  # ── Already exists — do PUT update ─────────────────────────────────────
  EXISTING_ID=$(grep -o '"id":[0-9]*' /tmp/servicedef-lookup.json | head -1 | grep -o '[0-9]*') || true

  if [ -z "${EXISTING_ID}" ]; then
    echo "ERROR: Service definition exists but could not parse its ID." >&2
    cat /tmp/servicedef-lookup.json >&2 2>/dev/null || true
    exit 1
  fi

  echo "Service definition already exists (id=${EXISTING_ID}). Updating..."

  UPDATE_CODE=$(curl -s -o /tmp/servicedef-response.json -w "%{http_code}" \
    -X PUT "${ENDPOINT}/${EXISTING_ID}" \
    -H "Content-Type: application/json" \
    -u "${AUTH}" \
    -d @"${SERVICEDEF_FILE}" 2>/dev/null) || true

  if [ "${UPDATE_CODE}" = "200" ]; then
    echo "Service definition 'lakeformation' updated successfully (id=${EXISTING_ID})."
    exit 0
  else
    echo "ERROR: Failed to update service definition. HTTP status: ${UPDATE_CODE}" >&2
    cat /tmp/servicedef-response.json >&2 2>/dev/null || true
    exit 1
  fi
else
  # ── Does not exist — do POST create ──────────────────────────────────────
  CREATE_CODE=$(curl -s -o /tmp/servicedef-response.json -w "%{http_code}" \
    -X POST "${ENDPOINT}" \
    -H "Content-Type: application/json" \
    -u "${AUTH}" \
    -d @"${SERVICEDEF_FILE}" 2>/dev/null) || true

  if [ "${CREATE_CODE}" = "200" ]; then
    echo "Service definition 'lakeformation' created successfully."
    exit 0
  else
    echo "ERROR: Failed to create service definition. HTTP status: ${CREATE_CODE}" >&2
    cat /tmp/servicedef-response.json >&2 2>/dev/null || true
    exit 1
  fi
fi
