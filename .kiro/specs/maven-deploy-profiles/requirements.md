# Requirements Document

## Introduction

This feature adds Maven-driven deployment profiles to the Ranger-to-Lake Formation Conversion Server project. Instead of requiring developers to manually run `docker compose` commands from the `deploy/` directory, Maven profiles orchestrate Docker Compose with parameterized configuration. Two distinct profiles are provided: one to deploy the conversion-server against an existing Ranger Admin instance, and one to bring up a local Ranger Admin stack (with its database and Solr dependencies) for developers who lack a running Ranger Admin.

## Glossary

- **Conversion_Server**: The Java application that syncs Apache Ranger policies to AWS Lake Formation permissions, packaged as a Docker container.
- **Ranger_Admin**: The Apache Ranger Admin web application that manages policies. Assumed to be running externally unless the developer explicitly starts the local stack.
- **Ranger_Admin_Stack**: The set of three Docker services (ranger-db, ranger-solr, ranger-admin) required to run a local Ranger Admin instance.
- **Deploy_Profile**: A Maven profile named `deploy-server` that builds and starts the conversion-server container via Docker Compose.
- **Ranger_Admin_Profile**: A Maven profile named `deploy-ranger-admin` that starts the Ranger_Admin_Stack via Docker Compose.
- **Config_Generator**: A Maven build step that produces `server-config-deploy.yaml` from Maven properties, since the ConfigLoader reads YAML directly and does not perform environment variable substitution.
- **Env_Generator**: A Maven build step that produces the `deploy/.env` file from Maven properties for AWS credentials passed as container environment variables.
- **Maven_Property**: A `-D` parameter passed on the Maven command line (e.g., `-Daws.region=us-east-1`).

## Requirements

### Requirement 1: Deploy Conversion Server Profile

**User Story:** As a developer, I want to deploy the conversion-server using a single Maven command with parameters, so that I can quickly start the server against an existing Ranger Admin without manually editing configuration files.

#### Acceptance Criteria

1. WHEN a developer runs `mvn docker-compose:up -Pdeploy-server` with required Maven_Property values, THE Deploy_Profile SHALL build the Docker image and start only the conversion-server service via Docker Compose.
2. THE Deploy_Profile SHALL accept the following Maven_Property values for AWS configuration: `aws.region`, `aws.account.id`, `aws.role.arn`, `aws.access.key.id`, `aws.secret.access.key`.
3. THE Deploy_Profile SHALL accept a `ranger.admin.url` Maven_Property to specify the Ranger Admin endpoint the conversion-server connects to.
4. THE Deploy_Profile SHALL accept a `ranger.admin.password` Maven_Property to specify the Ranger Admin authentication password.
5. THE Deploy_Profile SHALL accept Maven_Property values for principal mappings: `principal.user.mappings`, `principal.group.mappings`, `principal.role.mappings`, each as comma-separated `name=arn` pairs.
6. THE Deploy_Profile SHALL NOT start the ranger-db, ranger-solr, or ranger-admin services.
7. WHEN the Deploy_Profile is activated, THE Config_Generator SHALL produce a valid `server-config-deploy.yaml` file with all Maven_Property values substituted into the correct YAML fields before Docker Compose starts.
8. WHEN the Deploy_Profile is activated, THE Env_Generator SHALL produce a valid `deploy/.env` file containing `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, and `RANGER_ADMIN_PASSWORD` from the corresponding Maven_Property values.
9. IF a required Maven_Property is not provided, THEN THE Deploy_Profile SHALL fail the build with a descriptive error message identifying the missing property.

### Requirement 2: Deploy Local Ranger Admin Profile

**User Story:** As a developer who does not have access to a running Ranger Admin instance, I want to bring up a local Ranger Admin stack using a Maven command, so that I can develop and test the conversion-server locally.

#### Acceptance Criteria

1. WHEN a developer runs `mvn docker-compose:up -Pdeploy-ranger-admin`, THE Ranger_Admin_Profile SHALL start the ranger-db, ranger-solr, and ranger-admin services via Docker Compose.
2. THE Ranger_Admin_Profile SHALL NOT start the conversion-server service.
3. THE Ranger_Admin_Profile SHALL accept an optional `ranger.admin.password` Maven_Property to configure the Ranger Admin password, defaulting to `rangerR0cks!`.
4. THE Ranger_Admin_Profile SHALL accept an optional `ranger.version` Maven_Property to specify the Ranger image version, defaulting to `2.4.0`.
5. WHEN all three services in the Ranger_Admin_Stack are healthy, THE Ranger_Admin_Profile SHALL make the Ranger Admin UI accessible at `http://localhost:6080`.
6. WHEN a developer runs `mvn docker-compose:down -Pdeploy-ranger-admin`, THE Ranger_Admin_Profile SHALL stop and remove all Ranger_Admin_Stack containers.

### Requirement 3: Docker Compose File Separation

**User Story:** As a developer, I want the deployment Docker Compose configuration to be split into separate files for the conversion-server and the Ranger Admin stack, so that each Maven profile can operate on its own service set independently.

#### Acceptance Criteria

1. THE Deploy_Profile SHALL use a Docker Compose file that defines only the conversion-server service and the `rangernw` network.
2. THE Ranger_Admin_Profile SHALL use a Docker Compose file that defines only the ranger-db, ranger-solr, and ranger-admin services and the `rangernw` network.
3. THE Conversion_Server Docker Compose file SHALL configure the conversion-server to connect to the Ranger Admin URL specified by the `ranger.admin.url` Maven_Property via the `rangernw` network or an external network.
4. WHEN both profiles are used together (Ranger_Admin_Stack running, then Deploy_Profile started), THE Conversion_Server SHALL be able to reach the Ranger_Admin on the shared `rangernw` Docker network.

### Requirement 4: Configuration File Generation

**User Story:** As a developer, I want Maven to generate the server-config-deploy.yaml from command-line parameters, so that I do not have to manually edit YAML files with real AWS values before each deployment.

#### Acceptance Criteria

1. THE Config_Generator SHALL produce a `server-config-deploy.yaml` file that is valid YAML and readable by the ConfigLoader without modification.
2. THE Config_Generator SHALL substitute `aws.region`, `aws.account.id`, and `aws.role.arn` Maven_Property values into the `awsConfig` section of the generated YAML.
3. THE Config_Generator SHALL substitute `ranger.admin.url` and `ranger.admin.password` Maven_Property values into the `rangerConfig` section of the generated YAML.
4. THE Config_Generator SHALL parse `principal.user.mappings`, `principal.group.mappings`, and `principal.role.mappings` comma-separated `name=arn` pairs into the `principalMapping` section of the generated YAML.
5. THE Config_Generator SHALL use sensible defaults for optional fields: `policyRefreshIntervalMs` (30000), `maxLfRetries` (5), `lfRetryBackoffMs` (2000), `server.logLevel` (INFO), `server.shutdownTimeoutSeconds` (30).
6. WHEN optional Maven_Property values `sync.interval.ms`, `lf.max.retries`, `lf.retry.backoff.ms`, or `server.log.level` are provided, THE Config_Generator SHALL override the corresponding default values in the generated YAML.

### Requirement 5: Env File Generation

**User Story:** As a developer, I want Maven to generate the .env file from command-line parameters, so that AWS credentials are injected into the container without manual file editing.

#### Acceptance Criteria

1. THE Env_Generator SHALL produce a `deploy/.env` file containing `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `RANGER_ADMIN_PASSWORD` key-value pairs.
2. THE Env_Generator SHALL source values from the corresponding Maven_Property values: `aws.region`, `aws.access.key.id`, `aws.secret.access.key`, `ranger.admin.password`.
3. THE Env_Generator SHALL overwrite any existing `deploy/.env` file to ensure values are current.
4. THE Env_Generator SHALL produce the `.env` file before Docker Compose is invoked.

### Requirement 6: Default Property Values

**User Story:** As a developer, I want sensible defaults for optional parameters, so that I only need to specify the values that differ from the defaults.

#### Acceptance Criteria

1. THE Deploy_Profile SHALL default `ranger.admin.url` to `http://ranger-admin:6080` when the property is not provided.
2. THE Deploy_Profile SHALL default `ranger.admin.password` to `rangerR0cks!` when the property is not provided.
3. THE Deploy_Profile SHALL default `aws.region` to `us-east-1` when the property is not provided.
4. THE Deploy_Profile SHALL default `ranger.version` to `2.4.0` when the property is not provided.
5. THE Deploy_Profile SHALL require `aws.account.id`, `aws.role.arn`, `aws.access.key.id`, and `aws.secret.access.key` with no defaults, since these are environment-specific secrets.

### Requirement 7: Teardown Support

**User Story:** As a developer, I want to stop and clean up deployed containers using Maven commands, so that I can manage the full lifecycle from Maven.

#### Acceptance Criteria

1. WHEN a developer runs `mvn docker-compose:down -Pdeploy-server`, THE Deploy_Profile SHALL stop and remove the conversion-server container.
2. WHEN a developer runs `mvn docker-compose:down -Pdeploy-ranger-admin`, THE Ranger_Admin_Profile SHALL stop and remove all Ranger_Admin_Stack containers.
3. WHEN a developer runs `mvn docker-compose:down` with both profiles active, THE Maven build SHALL stop and remove containers from both profiles.
