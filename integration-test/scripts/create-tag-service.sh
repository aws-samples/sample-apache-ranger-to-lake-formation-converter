#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# create-tag-service.sh — Create the Ranger tag service instance used by
# the tag sync integration tests.
#
# Usage:
#   ./create-tag-service.sh [--ranger-url <url>]
#
# Defaults:
#   --ranger-url    http://localhost:6080
#
# Creates the "cl_tag" service instance of type "tag" (the built-in Ranger
# tag service backed by RangerServiceTag). Idempotent — exits 0 if the
# service already exists.
# ---------------------------------------------------------------------------
set -euo pipefail

RANGER_URL="http://localhost:6080"
AUTH="admin:rangerR0cks!"
TAG_SERVICE_NAME="cl_tag"

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

API="${RANGER_URL}/service/public/v2/api/service"
echo "Creating tag service '${TAG_SERVICE_NAME}' in Ranger Admin at ${RANGER_URL}..."

# Check if service already exists
LOOKUP_CODE=$(curl -sf -o /tmp/tag-svc-lookup.json -w "%{http_code}" \
  -X GET "${API}/name/${TAG_SERVICE_NAME}" \
  -u "${AUTH}" 2>/dev/null) || LOOKUP_CODE="000"

if [ "${LOOKUP_CODE}" = "200" ]; then
  echo "Tag service '${TAG_SERVICE_NAME}' already exists."
  exit 0
fi

# Create the tag service (type=tag is the built-in Ranger tag service type)
SERVICE_JSON="{\"name\":\"${TAG_SERVICE_NAME}\",\"type\":\"tag\",\"configs\":{}}"

CREATE_CODE=$(curl -sf -o /tmp/tag-svc-response.json -w "%{http_code}" \
  -X POST "${API}" \
  -H "Content-Type: application/json" \
  -u "${AUTH}" \
  -d "${SERVICE_JSON}" 2>/dev/null) || CREATE_CODE="000"

if [ "${CREATE_CODE}" = "200" ] || [ "${CREATE_CODE}" = "201" ]; then
  echo "Tag service '${TAG_SERVICE_NAME}' created successfully."
  exit 0
elif [ "${CREATE_CODE}" = "409" ]; then
  echo "Tag service '${TAG_SERVICE_NAME}' already exists (HTTP 409)."
  exit 0
else
  echo "ERROR: Failed to create tag service '${TAG_SERVICE_NAME}'. HTTP status: ${CREATE_CODE}" >&2
  cat /tmp/tag-svc-response.json >&2 2>/dev/null || true
  exit 1
fi
