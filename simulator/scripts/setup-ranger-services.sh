#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-ranger-services.sh — Idempotently register the Ranger service
# definitions and service instances required by the simulator.
#
# Services registered:
#   trino        — Trino query engine (type: trino)
#   emrfs        — EMR File System / S3 Access Grants (type: amazon-emr-emrfs)
#
# The lakeformation and cl_tag services are handled by the integration-test
# setup scripts (install-servicedef.sh / create-service-instance.sh /
# create-tag-service.sh). This script provisions only the simulator-specific
# services that those scripts do not cover.
#
# Usage:
#   ./setup-ranger-services.sh [--ranger-url <url>] [--repo-root <path>]
#
# Defaults:
#   --ranger-url   http://localhost:6080
#   --repo-root    two directories above this script (project root)
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RANGER_URL="http://localhost:6080"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
AUTH="admin:rangerR0cks!"
SERVICE_API="/service/public/v2/api/service"
SERVICEDEF_API="/service/public/v2/api/servicedef"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ranger-url)
      RANGER_URL="$2"
      shift 2
      ;;
    --repo-root)
      REPO_ROOT="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

# ---------------------------------------------------------------------------
# Helper: install_servicedef <name> <json-file> [strip-impl-class]
#   Registers a servicedef from a JSON file. If the servicedef already exists
#   it is left unchanged. If strip-impl-class=true, the implClass field and
#   lookupSupported flag are patched out before registration so that the
#   service can be created in a Docker environment without the plugin jar.
# ---------------------------------------------------------------------------
install_servicedef() {
  local NAME="$1"
  local FILE="$2"
  local STRIP_IMPL="${3:-false}"

  if [ ! -f "${FILE}" ]; then
    echo "ERROR: Servicedef file not found: ${FILE}" >&2
    exit 1
  fi

  echo "Checking servicedef '${NAME}'..."
  LOOKUP_CODE=$(curl -s -o /tmp/sd-lookup-${NAME}.json -w "%{http_code}" \
    -X GET "${RANGER_URL}${SERVICEDEF_API}/name/${NAME}" \
    -u "${AUTH}" 2>/dev/null) || LOOKUP_CODE="000"

  if [ "${LOOKUP_CODE}" = "200" ]; then
    echo "  Servicedef '${NAME}' already exists — skipping."
    return 0
  fi

  local PAYLOAD="${FILE}"
  if [ "${STRIP_IMPL}" = "true" ]; then
    python3 - "${FILE}" <<'PYEOF' > /tmp/sd-stripped-${NAME}.json
import json, sys
d = json.load(open(sys.argv[1]))
d.pop("implClass", None)
d["lookupSupported"] = False
print(json.dumps(d))
PYEOF
    PAYLOAD="/tmp/sd-stripped-${NAME}.json"
  fi

  echo "  Registering servicedef '${NAME}'..."
  CREATE_CODE=$(curl -s -o /tmp/sd-create-${NAME}.json -w "%{http_code}" \
    -X POST "${RANGER_URL}${SERVICEDEF_API}" \
    -H "Content-Type: application/json" \
    -u "${AUTH}" \
    -d @"${PAYLOAD}" 2>/dev/null) || CREATE_CODE="000"

  if [ "${CREATE_CODE}" = "200" ] || [ "${CREATE_CODE}" = "201" ]; then
    echo "  Servicedef '${NAME}' registered successfully."
  else
    echo "ERROR: Failed to register servicedef '${NAME}'. HTTP ${CREATE_CODE}" >&2
    cat /tmp/sd-create-${NAME}.json >&2 2>/dev/null || true
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# Helper: create_service <name> <type> <configs-json>
#   Creates a service instance idempotently. Treats HTTP 400 with a resource-
#   lookup warning as success (Ranger still creates the service in that case).
# ---------------------------------------------------------------------------
create_service() {
  local NAME="$1"
  local TYPE="$2"
  local CONFIGS="$3"

  echo "Checking service instance '${NAME}'..."
  LOOKUP_CODE=$(curl -s -o /tmp/svc-lookup-${NAME}.json -w "%{http_code}" \
    -X GET "${RANGER_URL}${SERVICE_API}/name/${NAME}" \
    -u "${AUTH}" 2>/dev/null) || LOOKUP_CODE="000"

  if [ "${LOOKUP_CODE}" = "200" ]; then
    echo "  Service instance '${NAME}' already exists — skipping."
    return 0
  fi

  local SERVICE_JSON="{\"name\":\"${NAME}\",\"type\":\"${TYPE}\",\"configs\":${CONFIGS}}"

  echo "  Creating service instance '${NAME}'..."
  CREATE_CODE=$(curl -s -o /tmp/svc-create-${NAME}.json -w "%{http_code}" \
    -X POST "${RANGER_URL}${SERVICE_API}" \
    -H "Content-Type: application/json" \
    -u "${AUTH}" \
    -d "${SERVICE_JSON}" 2>/dev/null) || CREATE_CODE="000"

  if [ "${CREATE_CODE}" = "200" ] || [ "${CREATE_CODE}" = "201" ]; then
    echo "  Service instance '${NAME}' created successfully."
    return 0
  elif [ "${CREATE_CODE}" = "409" ]; then
    echo "  Service instance '${NAME}' already exists (HTTP 409) — skipping."
    return 0
  elif [ "${CREATE_CODE}" = "400" ]; then
    BODY=$(cat /tmp/svc-create-${NAME}.json 2>/dev/null) || BODY=""
    if echo "${BODY}" | grep -q "Resource lookup will not be available"; then
      echo "  WARNING: Service '${NAME}' created with resource-lookup warning (HTTP 400). Continuing."
      return 0
    fi
    echo "ERROR: Failed to create service '${NAME}'. HTTP ${CREATE_CODE}" >&2
    echo "${BODY}" >&2
    exit 1
  else
    echo "ERROR: Failed to create service '${NAME}'. HTTP ${CREATE_CODE}" >&2
    cat /tmp/svc-create-${NAME}.json >&2 2>/dev/null || true
    exit 1
  fi
}

# ---------------------------------------------------------------------------
# 1. Trino
# ---------------------------------------------------------------------------
install_servicedef "trino" "${REPO_ROOT}/conf/ranger-servicedef-trino.json"
create_service "trino" "trino" "{}"

# ---------------------------------------------------------------------------
# 2. EMRFS (implClass stripped — plugin jar not present in Docker)
# ---------------------------------------------------------------------------
install_servicedef "amazon-emr-emrfs" \
  "${REPO_ROOT}/conf/ranger-servicedef-amazon-emr-emrfs.json" \
  "true"
create_service "emrfs" "amazon-emr-emrfs" "{}"

echo ""
echo "Simulator Ranger services provisioned successfully."
