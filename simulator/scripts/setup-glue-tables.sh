#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-glue-tables.sh — Idempotently create or update all 15 Glue tables
# required by the simulator (3 databases × 5 tables) with the full 8-column
# schema expected by HivePolicyGenerator.generateColumnPolicy().
#
# Databases: analytics, staging, default_sim
# Tables:    events, users, orders, products, sessions
# Columns:   id, name, value, created_at, status, amount, category, region
#            (all type: string)
#
# Usage:
#   ./setup-glue-tables.sh [--region <region>] [--account-id <id>] [--bucket <s3-uri>]
#
# Defaults:
#   --region      us-east-1
#   --account-id  (informational only, not required)
#   --bucket      s3://ranger-sim-placeholder
#
# Permissions required:
#   glue:GetTable, glue:CreateTable, glue:UpdateTable
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REGION="us-east-1"
ACCOUNT_ID=""
BUCKET="s3://ranger-sim-placeholder"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      REGION="$2"
      shift 2
      ;;
    --account-id)
      ACCOUNT_ID="$2"
      shift 2
      ;;
    --bucket)
      BUCKET="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

DATABASES=("analytics" "staging" "default_sim")
TABLES=("events" "users" "orders" "products" "sessions")

# ---------------------------------------------------------------------------
# Column schema: all 8 columns, all type string
# ---------------------------------------------------------------------------
COLUMNS_JSON='[
  {"Name": "id",         "Type": "string"},
  {"Name": "name",       "Type": "string"},
  {"Name": "value",      "Type": "string"},
  {"Name": "created_at", "Type": "string"},
  {"Name": "status",     "Type": "string"},
  {"Name": "amount",     "Type": "string"},
  {"Name": "category",   "Type": "string"},
  {"Name": "region",     "Type": "string"}
]'

# ---------------------------------------------------------------------------
# Helper: ensure_table <database> <table>
#   Creates the table if it does not exist; updates it (to apply schema
#   changes) if it does.
# ---------------------------------------------------------------------------
ensure_table() {
  local DB="$1"
  local TABLE="$2"
  local LOCATION="${BUCKET}/${DB}/${TABLE}/"

  local TABLE_INPUT
  TABLE_INPUT=$(python3 - <<PYEOF
import json
columns = $COLUMNS_JSON
table_input = {
    "Name": "${TABLE}",
    "StorageDescriptor": {
        "Columns": columns,
        "Location": "${LOCATION}",
        "InputFormat": "org.apache.hadoop.mapred.TextInputFormat",
        "OutputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
        "SerdeInfo": {
            "SerializationLibrary": "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe"
        }
    }
}
print(json.dumps(table_input))
PYEOF
)

  echo "  Checking table '${DB}.${TABLE}'..."

  local HTTP_CODE
  HTTP_CODE=$(aws glue get-table \
    --database-name "${DB}" \
    --name "${TABLE}" \
    --region "${REGION}" \
    --output json \
    > /tmp/glue-get-${DB}-${TABLE}.json 2>&1 && echo "200" || echo "404")

  if [ "${HTTP_CODE}" = "200" ]; then
    echo "    Table exists — updating schema..."
    aws glue update-table \
      --database-name "${DB}" \
      --table-input "${TABLE_INPUT}" \
      --region "${REGION}" \
      > /dev/null
    echo "    Updated '${DB}.${TABLE}'."
  else
    echo "    Table does not exist — creating..."
    aws glue create-table \
      --database-name "${DB}" \
      --table-input "${TABLE_INPUT}" \
      --region "${REGION}" \
      > /dev/null
    echo "    Created '${DB}.${TABLE}'."
  fi
}

# ---------------------------------------------------------------------------
# Main: iterate over all database/table combinations
# ---------------------------------------------------------------------------
CREATED=0
UPDATED=0

if [ -n "${ACCOUNT_ID}" ]; then
  echo "Account: ${ACCOUNT_ID}, Region: ${REGION}"
else
  echo "Region: ${REGION}"
fi
echo ""

for DB in "${DATABASES[@]}"; do
  echo "Database: ${DB}"
  for TABLE in "${TABLES[@]}"; do
    # Capture action for summary counter
    HTTP_CODE=$(aws glue get-table \
      --database-name "${DB}" \
      --name "${TABLE}" \
      --region "${REGION}" \
      --output json \
      > /dev/null 2>&1 && echo "200" || echo "404")

    ensure_table "${DB}" "${TABLE}"

    if [ "${HTTP_CODE}" = "200" ]; then
      UPDATED=$((UPDATED + 1))
    else
      CREATED=$((CREATED + 1))
    fi
  done
  echo ""
done

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo "-----------------------------------------------------------"
echo "Glue table setup complete."
echo "  Databases : ${#DATABASES[@]} (${DATABASES[*]})"
echo "  Tables    : ${#TABLES[@]} per database (${TABLES[*]})"
echo "  Columns   : id, name, value, created_at, status, amount, category, region"
echo "  Created   : ${CREATED}"
echo "  Updated   : ${UPDATED}"
echo "  Total     : $((CREATED + UPDATED)) / $((${#DATABASES[@]} * ${#TABLES[@]}))"
echo "-----------------------------------------------------------"
