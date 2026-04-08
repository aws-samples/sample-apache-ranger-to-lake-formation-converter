# Implementation Plan: Production Deployment

## Overview

Create a self-contained `deploy/` directory with Docker Compose, server config, environment file, and documentation for production/demo deployments of the Conversion Server against real AWS Lake Formation APIs. All deliverables are static configuration files — no application code changes.

## Tasks

- [x] 1. Create production Docker Compose file
  - [x] 1.1 Create `deploy/docker-compose.yml` with four services (`ranger-db`, `ranger-solr`, `ranger-admin`, `conversion-server`) on a shared bridge network
    - Mirror `ranger-db`, `ranger-solr`, and `ranger-admin` service definitions from `integration-test/docker/docker-compose.yml` (same images, health checks, dependency ordering)
    - Configure `conversion-server` service with `build.context: ..` and `build.dockerfile: Dockerfile`
    - Set `DRY_RUN_ENABLED: "false"` in the `conversion-server` environment
    - Do NOT set `DRY_RUN_OUTPUT_DIR` or mount a dry-run volume
    - Mount `./server-config-deploy.yaml` as `/app/config-deploy.yaml:ro` inside the container
    - Reference `env_file: .env` for AWS credentials and account-specific values
    - Pass `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` as container environment variables for the AWS SDK default credential chain
    - Set entrypoint to use `/app/config-deploy.yaml` as the config path
    - _Requirements: 1.1, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 2. Create production server configuration
  - [x] 2.1 Create `deploy/server-config-deploy.yaml` based on `conf/server-config.yaml`
    - Set `rangerConfig.rangerAdminUrl` to `http://ranger-admin:6080` (Docker network hostname)
    - Set `rangerConfig.password` to a placeholder value the operator replaces (e.g., `CHANGE_ME`)
    - Set `awsConfig.region`, `awsConfig.catalogId`, `awsConfig.roleArn` to descriptive placeholder values with guiding comments (e.g., `your-region-here`, `000000000000`, `arn:aws:iam::000000000000:role/YourRole`)
    - Set `policyRefreshIntervalMs: 30000`
    - Set `maxLfRetries: 5` and `lfRetryBackoffMs: 2000`
    - Include `principalMapping` section with example placeholder entries for user, group, and role mappings
    - Set `server.logLevel: INFO` and `server.shutdownTimeoutSeconds: 30`
    - Include comments explaining that the operator must edit AWS-specific values directly in this file since `ConfigLoader` does not perform `${VAR}` substitution
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 3. Create environment file and update .gitignore
  - [x] 3.1 Create `deploy/.env` with placeholder entries
    - Include `AWS_REGION`, `AWS_ACCOUNT_ID`, `AWS_ROLE_ARN`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
    - Include `RANGER_ADMIN_PASSWORD`
    - Include reference entries for principal mappings (at least one user, group, and role mapping)
    - Add inline comments explaining each variable and its expected format
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 3.2 Add `deploy/.env` to the project root `.gitignore`
    - Append `deploy/.env` entry to `.gitignore`
    - _Requirements: 3.5, 5.1_

- [x] 4. Checkpoint — Verify configuration files
  - Ensure all YAML files are syntactically valid, ask the user if questions arise.

- [x] 5. Create deployment documentation
  - [x] 5.1 Create `deploy/README.md`
    - Document the purpose of the `deploy/` directory and how it differs from `integration-test/docker/`
    - List the steps to configure `deploy/.env` with real AWS values
    - Explain that `server-config-deploy.yaml` must be edited directly for `awsConfig` and `principalMapping` fields
    - Provide `docker compose up --build` and `docker compose down` commands
    - Explain how to verify the server is running (health checks, logs)
    - Document how to view server logs (`docker compose logs conversion-server`) and troubleshoot common issues
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 6. Final checkpoint — Review all deliverables
  - Ensure all files are created and consistent with each other, ask the user if questions arise.

## Notes

- No application code changes are needed — all deliverables are static config files and documentation
- Property-based testing is not applicable since no new logic is introduced
- The existing `integration-test/docker/` files must not be modified
- The `ConfigLoader` reads YAML directly without `${VAR}` substitution, so `server-config-deploy.yaml` uses literal placeholder values the operator edits
- AWS credentials flow through Docker Compose env vars → container env → AWS SDK default credential chain
