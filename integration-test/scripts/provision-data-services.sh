#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# provision-data-services.sh — Install servicedefs and create service instances
# for ALL Ranger data services the simulator/pipeline exercises:
#   lakeformation, hive, trino, amazon-emr-emrfs, amazon-emr-spark
#
# This complements install-servicedef.sh / create-service-instance.sh (which
# handle lakeformation only) by provisioning the full multi-service topology
# so the Ranger -> Cedar -> LF translation path is exercised for every source
# service type, not just lakeformation.
#
# Usage:
#   ./provision-data-services.sh [--ranger-url <url>]
#
# Defaults:
#   --ranger-url    http://localhost:6080
#
# Idempotent: skips servicedefs/instances that already exist.
#
# Notes:
#   - hive and trino servicedefs require JDBC connection configs for instance
#     creation validation. We supply dummy values — the pipeline only reads
#     authored policies, it never performs live resource lookup, so the
#     connection is never used.
#   - amazon-emr-emrfs and amazon-emr-spark ship custom implClass values whose
#     Java classes are not on the Ranger Admin classpath. We patch implClass to
#     a built-in Ranger class (RangerServiceTag) so instance creation succeeds,
#     exactly as create-service-instance.sh does for lakeformation. Resource
#     lookup is unavailable (unused), policy CRUD is unaffected.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF_DIR="$(cd "${SCRIPT_DIR}/../../conf" && pwd)"
RANGER_URL="http://localhost:6080"
AUTH="admin:rangerR0cks!"
BUILTIN_IMPL_CLASS="org.apache.ranger.services.tag.RangerServiceTag"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ranger-url) RANGER_URL="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

SVCDEF_API="${RANGER_URL}/service/public/v2/api/servicedef"
SVC_API="${RANGER_URL}/service/public/v2/api/service"

# service_type : dummy instance configs (JSON object)
hive_configs='{"username":"sim","password":"sim","jdbc.driverClassName":"org.apache.hive.jdbc.HiveDriver","jdbc.url":"jdbc:hive2://localhost:10000"}'
trino_configs='{"username":"sim","password":"sim","jdbc.driverClassName":"io.trino.jdbc.TrinoDriver","jdbc.url":"jdbc:trino://localhost:8080"}'
empty_configs='{}'

# Service types that need implClass patched (custom class not on Admin classpath)
needs_impl_patch() {
  case "$1" in
    amazon-emr-emrfs|amazon-emr-spark) return 0 ;;
    *) return 1 ;;
  esac
}

configs_for() {
  case "$1" in
    hive)  echo "${hive_configs}" ;;
    trino) echo "${trino_configs}" ;;
    *)     echo "${empty_configs}" ;;
  esac
}

install_servicedef() {
  local stype="$1"
  local def_file="${CONF_DIR}/ranger-servicedef-${stype}.json"
  [ -f "${def_file}" ] || { echo "ERROR: servicedef not found: ${def_file}" >&2; exit 1; }

  local code
  code=$(curl -s -o /tmp/psd-lookup.json -w "%{http_code}" \
    -X GET "${SVCDEF_API}/name/${stype}" -u "${AUTH}" 2>/dev/null) || true

  if [ "${code}" = "200" ]; then
    echo "  servicedef '${stype}' already installed."
  else
    code=$(curl -s -o /tmp/psd-create.json -w "%{http_code}" \
      -X POST "${SVCDEF_API}" -H "Content-Type: application/json" \
      -u "${AUTH}" -d @"${def_file}" 2>/dev/null) || true
    if [ "${code}" = "200" ]; then
      echo "  servicedef '${stype}' installed."
    else
      echo "ERROR: failed to install servicedef '${stype}' (HTTP ${code})." >&2
      cat /tmp/psd-create.json >&2 2>/dev/null || true
      exit 1
    fi
  fi

  # Patch implClass to a built-in class if the custom class is unavailable.
  if needs_impl_patch "${stype}"; then
    curl -s -o /tmp/psd-body.json -w "%{http_code}" \
      -X GET "${SVCDEF_API}/name/${stype}" -u "${AUTH}" >/dev/null 2>&1 || true
    if grep -q '"implClass"' /tmp/psd-body.json 2>/dev/null \
       && ! grep -q "${BUILTIN_IMPL_CLASS}" /tmp/psd-body.json 2>/dev/null; then
      local sid
      sid=$(grep -o '"id":[0-9]*' /tmp/psd-body.json | head -1 | grep -o '[0-9]*') || true
      python3 - "${stype}" "${BUILTIN_IMPL_CLASS}" <<'PY' > /tmp/psd-patched.json
import json, sys
d = json.load(open("/tmp/psd-body.json"))
d["implClass"] = sys.argv[2]
json.dump(d, sys.stdout)
PY
      local pcode
      pcode=$(curl -s -o /tmp/psd-patch-resp.json -w "%{http_code}" \
        -X PUT "${SVCDEF_API}/${sid}" -H "Content-Type: application/json" \
        -u "${AUTH}" -d @/tmp/psd-patched.json 2>/dev/null) || true
      [ "${pcode}" = "200" ] \
        && echo "    patched implClass -> ${BUILTIN_IMPL_CLASS} (id=${sid})." \
        || echo "    WARNING: implClass patch failed (HTTP ${pcode}); instance creation may fail." >&2
    fi
  fi
}

create_instance() {
  local stype="$1"
  local code
  code=$(curl -s -o /tmp/psi-lookup.json -w "%{http_code}" \
    -X GET "${SVC_API}/name/${stype}" -u "${AUTH}" 2>/dev/null) || true
  if [ "${code}" = "200" ]; then
    echo "  service instance '${stype}' already exists."
    return 0
  fi

  local svc_json
  svc_json=$(printf '{"name":"%s","type":"%s","configs":%s}' \
    "${stype}" "${stype}" "$(configs_for "${stype}")")

  code=$(curl -s -o /tmp/psi-create.json -w "%{http_code}" \
    -X POST "${SVC_API}" -H "Content-Type: application/json" \
    -u "${AUTH}" -d "${svc_json}" 2>/dev/null) || true

  if [ "${code}" = "200" ] || [ "${code}" = "201" ] || [ "${code}" = "409" ]; then
    echo "  service instance '${stype}' created."
  elif [ "${code}" = "400" ] && grep -q "Resource lookup will not be available" /tmp/psi-create.json 2>/dev/null; then
    # Benign: instance is created; only live resource lookup is unavailable.
    echo "  service instance '${stype}' created (resource-lookup warning)."
  else
    echo "ERROR: failed to create service instance '${stype}' (HTTP ${code})." >&2
    cat /tmp/psi-create.json >&2 2>/dev/null || true
    exit 1
  fi
}

DATA_SERVICES="lakeformation hive trino amazon-emr-emrfs amazon-emr-spark"

echo "Provisioning data services into Ranger Admin at ${RANGER_URL}..."
for stype in ${DATA_SERVICES}; do
  echo "[${stype}]"
  install_servicedef "${stype}"
  create_instance "${stype}"
done
echo "All data services provisioned."
