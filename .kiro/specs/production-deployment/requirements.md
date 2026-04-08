# Requirements Document

## Introduction

This feature introduces a dedicated production/demo deployment setup for the Ranger-to-Lake Formation Conversion Server. Currently, the only Docker Compose environment lives under `integration-test/docker/` and is hardcoded for dry-run mode (`DRY_RUN_ENABLED=true`). When running against real AWS Lake Formation APIs for demos or production use, the only option is to modify integration test configs, which breaks isolation between testing and deployment concerns.

This feature creates a separate `deploy/` directory with its own `docker-compose.yml`, server configuration, and environment file so that production/demo deployments are fully isolated from the integration test infrastructure. The deployment reuses the same Dockerfile and server code but configures the server to use real AWS credentials and Lake Formation APIs.

## Glossary

- **Conversion_Server**: The containerized Java application (`ConversionServerMain`) that continuously syncs Apache Ranger policies to AWS Lake Formation permissions.
- **Deploy_Compose**: The new Docker Compose file located at `deploy/docker-compose.yml` that defines the production/demo deployment stack.
- **IT_Compose**: The existing Docker Compose file at `integration-test/docker/docker-compose.yml` used exclusively for integration testing in dry-run mode.
- **Deploy_Config**: The server configuration YAML file at `deploy/server-config-deploy.yaml` used by the production/demo deployment.
- **IT_Config**: The existing server configuration at `integration-test/docker/server-config-it.yaml` used exclusively for integration tests.
- **Env_File**: The `.env` file at `deploy/.env` containing sensitive and environment-specific values (AWS credentials, account IDs, IAM role ARNs) that are injected into the Deploy_Compose services.
- **DRY_RUN_ENABLED**: An environment variable checked by the Conversion_Server at startup. When set to `"true"`, the server uses `DryRunLakeFormationClient`; when absent or `"false"`, the server uses the real `LakeFormationClient`.
- **Principal_Mapping**: The configuration section that maps Ranger users, groups, and roles to AWS IAM principal ARNs.

## Requirements

### Requirement 1: Isolated Deployment Directory Structure

**User Story:** As a developer, I want a separate `deploy/` directory for production/demo deployment files, so that deployment configuration is fully isolated from integration test infrastructure.

#### Acceptance Criteria

1. THE Deploy_Compose SHALL reside at the path `deploy/docker-compose.yml`, separate from the IT_Compose at `integration-test/docker/docker-compose.yml`.
2. THE Deploy_Config SHALL reside at the path `deploy/server-config-deploy.yaml`, separate from the IT_Config at `integration-test/docker/server-config-it.yaml`.
3. THE Env_File SHALL reside at the path `deploy/.env` and contain placeholder values for all environment-specific settings.
4. WHEN the Deploy_Compose is created or modified, THE IT_Compose SHALL remain unchanged.
5. WHEN the Deploy_Config is created or modified, THE IT_Config SHALL remain unchanged.

### Requirement 2: Production Docker Compose Configuration

**User Story:** As a developer, I want a Docker Compose file for production/demo deployment that builds the Conversion_Server with real AWS API access, so that I can run the server against Lake Formation without modifying integration test files.

#### Acceptance Criteria

1. THE Deploy_Compose SHALL define a `conversion-server` service that builds from the project root Dockerfile.
2. THE Deploy_Compose SHALL set `DRY_RUN_ENABLED` to `"false"` (or omit the variable) so the Conversion_Server uses the real LakeFormationClient.
3. THE Deploy_Compose SHALL mount the Deploy_Config as the server configuration file inside the container.
4. THE Deploy_Compose SHALL reference the Env_File to inject AWS credentials and account-specific values into the container environment.
5. THE Deploy_Compose SHALL define a `ranger-admin` service (and its dependencies `ranger-db` and `ranger-solr`) with the same images and health checks as the IT_Compose, so the Conversion_Server can connect to a local Ranger Admin instance.
6. THE Deploy_Compose SHALL place all services on a shared Docker bridge network.

### Requirement 3: Environment File for Sensitive Configuration

**User Story:** As a developer, I want an `.env` file with clearly documented placeholders for AWS credentials and account-specific values, so that I can configure the deployment for different AWS accounts without editing YAML files.

#### Acceptance Criteria

1. THE Env_File SHALL contain placeholder entries for `AWS_REGION`, `AWS_ACCOUNT_ID`, `AWS_ROLE_ARN`, `AWS_ACCESS_KEY_ID`, and `AWS_SECRET_ACCESS_KEY`.
2. THE Env_File SHALL contain placeholder entries for principal mappings: at least one user mapping, one group mapping, and one role mapping.
3. THE Env_File SHALL contain a placeholder entry for `RANGER_ADMIN_PASSWORD`.
4. THE Env_File SHALL include inline comments explaining each variable and its expected format.
5. THE Env_File SHALL be listed in `.gitignore` to prevent accidental commit of sensitive credentials.

### Requirement 4: Production Server Configuration

**User Story:** As a developer, I want a server configuration YAML file tailored for production/demo use, so that the Conversion_Server connects to real AWS services with appropriate sync intervals and retry settings.

#### Acceptance Criteria

1. THE Deploy_Config SHALL reference environment variable placeholders (e.g., `${AWS_REGION}`, `${AWS_ACCOUNT_ID}`, `${AWS_ROLE_ARN}`) for all AWS-specific values in the `awsConfig` section.
2. THE Deploy_Config SHALL configure `policyRefreshIntervalMs` to 30000 (30 seconds), appropriate for production use.
3. THE Deploy_Config SHALL configure `maxLfRetries` to 5 and `lfRetryBackoffMs` to 2000, appropriate for real AWS API calls.
4. THE Deploy_Config SHALL include a `principalMapping` section with placeholder values that correspond to Env_File variables.
5. THE Deploy_Config SHALL set the `rangerConfig.rangerAdminUrl` to the Docker network hostname of the Ranger Admin service defined in the Deploy_Compose.
6. THE Deploy_Config SHALL include a `server` section with `logLevel` set to `INFO` and `shutdownTimeoutSeconds` set to 30.

### Requirement 5: Gitignore Updates for Deployment Secrets

**User Story:** As a developer, I want the `.gitignore` to exclude deployment environment files, so that AWS credentials and secrets are not accidentally committed to version control.

#### Acceptance Criteria

1. WHEN the `deploy/.env` file exists, THE `.gitignore` file SHALL contain an entry that excludes `deploy/.env` from version control.

### Requirement 6: Deployment Documentation

**User Story:** As a developer, I want a README in the `deploy/` directory that explains how to configure and launch a production/demo deployment, so that other team members can set up the deployment without prior knowledge.

#### Acceptance Criteria

1. THE `deploy/README.md` SHALL document the purpose of the deployment directory and how it differs from the integration test setup.
2. THE `deploy/README.md` SHALL list the steps to configure the Env_File with real AWS values.
3. THE `deploy/README.md` SHALL provide the Docker Compose commands to build and start the deployment stack.
4. THE `deploy/README.md` SHALL explain how to verify the Conversion_Server is running and connected to both Ranger Admin and AWS Lake Formation.
5. THE `deploy/README.md` SHALL document how to view server logs and troubleshoot common issues.
