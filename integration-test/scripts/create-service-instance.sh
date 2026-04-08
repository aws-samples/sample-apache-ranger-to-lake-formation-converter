#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# create-service-instance.sh — Create the lakeformation service instance in Ranger Admin.
#
# Usage:
#   ./create-service-instance.sh [--ranger-url <url>]
#
# Defaults:
#   --ranger-url    http://localhost:6080
#
# Checks if the service instance exists first; if so, exits successfully.
# Creates the instance via POST if it does not exist.
# Handles HTTP 409 (already exists) as success.
# ---------------------------------------------------------------------------
set -euo pipefail

RANGER_URL="http://localhost:6080"
AUTH="admin:rangerR0cks!"
API_PATH="/service/public/v2/api/service"
SERVICE_NAME="lakeformation"

# ── Argument parsing ───────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ranger-url)
      RANGER_URL="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

SERVICEDEF_API="${RANGER_URL}/service/public/v2/api/servicedef"
ENDPOINT="${RANGER_URL}${API_PATH}"
echo "Creating '${SERVICE_NAME}' service instance in Ranger Admin at ${RANGER_URL}..."

# ── Patch servicedef implClass for Docker compatibility ────────────────────
# The custom LakeFormationResourceLookupService class isn't available in the
# Ranger Admin Docker container. Replace it with a built-in Ranger class so
# that service instance creation succeeds.
SERVICEDEF_CODE=$(curl -s -o /tmp/servicedef-body.json -w "%{http_code}" \
  -X GET "${SERVICEDEF_API}/name/${SERVICE_NAME}" \
  -u "${AUTH}" 2>/dev/null) || true

if [ "${SERVICEDEF_CODE}" = "200" ]; then
  if grep -q "LakeFormationResourceLookupService" /tmp/servicedef-body.json 2>/dev/null; then
    echo "Patching servicedef implClass for Docker compatibility..."
    # Replace the custom implClass with a built-in Ranger class
    sed 's/com\.amazonaws\.policyconverters\.ranger\.service\.LakeFormationResourceLookupService/org.apache.ranger.services.tag.RangerServiceTag/g' \
      /tmp/servicedef-body.json > /tmp/servicedef-patched.json

    # Extract the servicedef ID
    SERVICEDEF_ID=$(grep -o '"id":[0-9]*' /tmp/servicedef-patched.json | head -1 | grep -o '[0-9]*') || true
    if [ -n "${SERVICEDEF_ID}" ]; then
      PATCH_CODE=$(curl -s -o /tmp/servicedef-patch-response.json -w "%{http_code}" \
        -X PUT "${SERVICEDEF_API}/${SERVICEDEF_ID}" \
        -H "Content-Type: application/json" \
        -u "${AUTH}" \
        -d @/tmp/servicedef-patched.json 2>/dev/null) || true
      if [ "${PATCH_CODE}" = "200" ]; then
        echo "Servicedef implClass patched successfully (id=${SERVICEDEF_ID})."
      else
        echo "WARNING: Failed to patch servicedef implClass (HTTP ${PATCH_CODE}). Continuing anyway..." >&2
      fi
    fi
  fi
fi

# ── Check if service instance already exists ───────────────────────────────
LOOKUP_CODE=$(curl -s -o /tmp/service-lookup.json -w "%{http_code}" \
  -X GET "${ENDPOINT}/name/${SERVICE_NAME}" \
  -u "${AUTH}" 2>/dev/null) || true

if [ "${LOOKUP_CODE}" = "200" ]; then
  echo "Service instance '${SERVICE_NAME}' already exists."
  exit 0
fi

# ── Create service instance ────────────────────────────────────────────────
SERVICE_JSON='{"name":"lakeformation","type":"lakeformation","configs":{"aws.region":"us-east-1","aws.catalog.id":"123456789012"}}'

CREATE_CODE=$(curl -s -o /tmp/service-response.json -w "%{http_code}" \
  -X POST "${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -u "${AUTH}" \
  -d "${SERVICE_JSON}" 2>/dev/null) || true

if [ "${CREATE_CODE}" = "200" ]; then
  echo "Service instance '${SERVICE_NAME}' created successfully."
  exit 0
elif [ "${CREATE_CODE}" = "409" ]; then
  echo "Service instance '${SERVICE_NAME}' already exists (HTTP 409)."
  exit 0
else
  echo "ERROR: Failed to create service instance '${SERVICE_NAME}'. HTTP status: ${CREATE_CODE}" >&2
  cat /tmp/service-response.json >&2 2>/dev/null || true
  exit 1
fi
