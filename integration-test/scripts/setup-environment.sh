#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Build IT image from pre-built JAR
JAR=$(ls "${REPO_ROOT}"/target/*-jar-with-dependencies.jar 2>/dev/null | head -1)
if [ -z "${JAR}" ]; then
  echo "ERROR: No fat JAR (*-jar-with-dependencies.jar) found in ${REPO_ROOT}/target/. Run 'mvn package -DskipTests' first." >&2
  exit 1
fi

docker build -f "${REPO_ROOT}/integration-test/docker/Dockerfile.it" \
  --build-arg "JAR_FILE=${JAR#${REPO_ROOT}/}" \
  -t ranger-lf-it:local \
  "${REPO_ROOT}"

cd "${REPO_ROOT}/integration-test/docker"

# Start Ranger services first
docker compose up -d ranger-db ranger-solr ranger-admin

# Wait for Ranger Admin (120s timeout)
TIMEOUT=120; INTERVAL=5; elapsed=0
until curl -sf http://localhost:6080/login.jsp >/dev/null 2>&1; do
  sleep $INTERVAL; elapsed=$((elapsed + INTERVAL))
  [ $elapsed -ge $TIMEOUT ] && echo "ERROR: Ranger Admin did not start in ${TIMEOUT}s" >&2 && exit 1
done
echo "Ranger Admin is ready."

# Provision servicedef, service instance, and tag service
"${SCRIPT_DIR}/install-servicedef.sh" --ranger-url http://localhost:6080
"${SCRIPT_DIR}/create-service-instance.sh" --ranger-url http://localhost:6080
"${SCRIPT_DIR}/create-tag-service.sh" --ranger-url http://localhost:6080

# Start conversion-server
mkdir -p "${REPO_ROOT}/integration-test/docker/dry-run-output"
docker compose up -d conversion-server

# Wait for conversion-server JVM to be running (120s timeout)
elapsed=0
until docker compose exec -T conversion-server pgrep -f 'java.*app.jar' >/dev/null 2>&1; do
  sleep 2; elapsed=$((elapsed + 2))
  [ $elapsed -ge 120 ] && echo "ERROR: conversion-server did not start" >&2 && exit 1
done
echo "conversion-server is ready."
