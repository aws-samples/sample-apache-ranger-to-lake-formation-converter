# Ranger Integration Test Infrastructure

Integration tests for the Ranger Lake Formation Sync Plugin. Tests run against a real Apache Ranger Admin instance provisioned via Docker Compose.

## Prerequisites

- **Docker** (with Docker Compose v2)
- **Java 8**
- **Maven 3.6+**

## Quickstart

Run everything with a single command:

```bash
./integration-test/scripts/run-integration-tests.sh
```

This provisions the Ranger stack, runs `mvn verify -Pintegration-test`, and tears down — regardless of test outcome. The exit code reflects whether tests passed or failed.

To keep the stack running after tests (useful for debugging):

```bash
./integration-test/scripts/run-integration-tests.sh --skip-teardown
```

## Manual Step-by-Step

For debugging or running individual tests:

```bash
# 1. Start the Ranger stack (Ranger Admin, Solr, PostgreSQL)
./integration-test/scripts/start-ranger.sh

# 2. Verify Ranger Admin is up
curl -sf http://localhost:6080/login.jsp > /dev/null && echo "Ready"

# 3. Run integration tests
mvn verify -Pintegration-test

# 4. Tear down
./integration-test/scripts/stop-ranger.sh
```

Ranger Admin is available at `http://localhost:6080` (credentials: `admin` / `rangerR0cks!`).

## Directory Structure

```
integration-test/
├── docker/              Docker Compose file for the Ranger stack
│   └── docker-compose.yml
├── scripts/             Lifecycle and utility scripts
│   ├── start-ranger.sh        Start stack + wait for health
│   ├── stop-ranger.sh         Idempotent teardown
│   └── run-integration-tests.sh  Full lifecycle runner
├── k8s/                 Kubernetes manifests (EKS Fargate — future)
├── ec2/                 EC2 deployment scripts (future)
└── DEPLOYMENT-STRATEGIES.md   Comparison of deployment options
```

Integration test Java sources live in `src/integration-test/java/` and are compiled only when the `integration-test` Maven profile is active.

## Script Reference

| Script | Purpose |
|--------|---------|
| `start-ranger.sh` | Starts the Docker Compose stack and polls until Ranger Admin is healthy. Supports `--timeout`, `--interval`, `--compose-file` flags. |
| `stop-ranger.sh` | Tears down the stack. Always exits 0 (idempotent). Supports `--compose-file` flag. |
| `run-integration-tests.sh` | Full lifecycle: provision → test → teardown. Supports `--skip-teardown` flag. |

## Future Phases

- **CI/CD**: GitHub Actions workflow for automated integration testing on PRs
- **EKS Fargate**: Kubernetes manifests for shared cloud-based test environments
- **EC2**: Scripts for persistent test environments on EC2 instances

See [DEPLOYMENT-STRATEGIES.md](DEPLOYMENT-STRATEGIES.md) for a comparison of all deployment options.
