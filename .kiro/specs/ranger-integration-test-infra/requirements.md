# Requirements Document

## Introduction

This document defines the requirements for Phase 1 of integration test infrastructure for the Ranger Lake Formation Sync Plugin. The goal is to automate the provisioning of a fully functional Apache Ranger environment (Ranger Admin, Solr, Postgres) that integration tests can run against. The document evaluates multiple deployment strategies — Docker Compose (local, pre-built images), EKS Fargate, EC2 with custom-built images — and defines requirements for the chosen approach(es) so that the team can make an informed decision and begin implementation.

## Glossary

- **Ranger_Admin**: The Apache Ranger administration server (accessible on port 6080) that manages policies and service definitions. Uses the `apache/ranger` Docker image.
- **Ranger_Solr**: An Apache Solr instance configured for Ranger audit storage. Uses the `apache/ranger-solr` Docker image, exposed on port 8983.
- **Ranger_DB**: A PostgreSQL database instance used by Ranger Admin for persistent storage. Uses the `apache/ranger-db` Docker image.
- **Ranger_Stack**: The combined set of Ranger_Admin, Ranger_Solr, and Ranger_DB containers and their networking, forming a complete Ranger environment.
- **Docker_Compose_Orchestrator**: The Docker Compose tool that defines and runs the multi-container Ranger_Stack from a `docker-compose.yml` file using pre-built images from Docker Hub.
- **Integration_Test_Runner**: The Maven test execution process (via `maven-failsafe-plugin`) that runs integration tests tagged or located in a dedicated source set, separate from unit tests.
- **Health_Check_Probe**: An automated HTTP check against Ranger_Admin's login endpoint (`/login.jsp` on port 6080) to confirm the Ranger_Stack is ready to accept API requests.
- **EKS_Fargate_Deployment**: A Kubernetes-based deployment of the Ranger_Stack on AWS EKS using Fargate serverless compute, defined via Kubernetes manifests or Helm charts.
- **EC2_Deployment**: A deployment model where Docker images are built locally (or in CI) and pushed to an EC2 instance running Docker, with the Ranger_Stack started via Docker Compose or shell scripts on the instance.
- **CI_Pipeline**: The continuous integration system (e.g., GitHub Actions, Jenkins) that automates build, test, and deployment steps including Ranger_Stack lifecycle management.
- **Startup_Script**: A shell script (`start-ranger.sh`) that wraps Docker Compose commands to bring up the Ranger_Stack, wait for health checks, and report readiness.
- **Teardown_Script**: A shell script (`stop-ranger.sh`) that wraps Docker Compose commands to stop and remove the Ranger_Stack containers, network, and volumes.
- **Ranger_Version**: The version of Apache Ranger Docker images used, defaulting to `2.4.0` to match the project's `ranger.version` Maven property, with support for overriding to other versions (e.g., `2.8.0`).

## Requirements

### Requirement 1: Deployment Strategy Evaluation

**User Story:** As a developer, I want a documented comparison of deployment options for the Ranger integration test environment, so that the team can choose the approach that balances simplicity, cost, and CI compatibility.

#### Acceptance Criteria

1. THE Integration_Test_Runner documentation SHALL include a comparison matrix covering the following deployment strategies: Docker Compose (local, pre-built images), EKS Fargate, EC2 with locally-built images, and Testcontainers (programmatic Docker management from Java).
2. WHEN evaluating each deployment strategy, THE comparison matrix SHALL assess the following dimensions: setup complexity, CI/CD compatibility, cost, startup time, teardown reliability, and local developer experience.
3. THE comparison matrix SHALL include a recommended strategy with a rationale based on the evaluation dimensions.

### Requirement 2: Docker Compose Ranger Stack Definition

**User Story:** As a developer, I want a Docker Compose file that defines the complete Ranger stack using pre-built Docker Hub images, so that I can spin up a Ranger environment with a single command.

#### Acceptance Criteria

1. THE Docker_Compose_Orchestrator SHALL define services for Ranger_DB, Ranger_Solr, and Ranger_Admin in a single `docker-compose.yml` file.
2. THE Docker_Compose_Orchestrator SHALL use pre-built images from Docker Hub: `apache/ranger-db`, `apache/ranger-solr`, and `apache/ranger` with a configurable image tag defaulting to Ranger_Version.
3. THE Docker_Compose_Orchestrator SHALL create a dedicated Docker network named `rangernw` for inter-container communication.
4. THE Docker_Compose_Orchestrator SHALL expose Ranger_Admin on host port 6080 and Ranger_Solr on host port 8983.
5. THE Docker_Compose_Orchestrator SHALL define service startup dependencies so that Ranger_DB and Ranger_Solr start before Ranger_Admin.
6. THE Docker_Compose_Orchestrator SHALL configure Health_Check_Probes for each service to determine container readiness.
7. WHEN the `RANGER_VERSION` environment variable is set, THE Docker_Compose_Orchestrator SHALL use the specified version as the image tag instead of the default Ranger_Version.

### Requirement 3: Startup and Teardown Automation

**User Story:** As a developer, I want scripts that automate starting and stopping the Ranger stack, so that I can reliably manage the test environment lifecycle without manual Docker commands.

#### Acceptance Criteria

1. THE Startup_Script SHALL invoke Docker Compose to bring up the Ranger_Stack in detached mode.
2. THE Startup_Script SHALL poll the Health_Check_Probe at a configurable interval (defaulting to 5 seconds) until Ranger_Admin responds with HTTP 200 or a configurable timeout (defaulting to 120 seconds) is reached.
3. IF the Health_Check_Probe does not return HTTP 200 within the timeout period, THEN THE Startup_Script SHALL print container logs for each service and exit with a non-zero exit code.
4. WHEN the Health_Check_Probe returns HTTP 200, THE Startup_Script SHALL print a readiness message including the Ranger_Admin URL.
5. THE Teardown_Script SHALL invoke Docker Compose to stop and remove all Ranger_Stack containers, the `rangernw` network, and associated volumes.
6. THE Teardown_Script SHALL exit with exit code 0 even when containers are already stopped or do not exist.

### Requirement 4: Maven Integration Test Profile

**User Story:** As a developer, I want a dedicated Maven profile for integration tests, so that integration tests run separately from unit tests and can be triggered explicitly.

#### Acceptance Criteria

1. THE Integration_Test_Runner SHALL define a Maven profile named `integration-test` in `pom.xml`.
2. WHEN the `integration-test` profile is activated, THE Integration_Test_Runner SHALL execute tests located in `src/integration-test/java` using the `maven-failsafe-plugin`.
3. THE Integration_Test_Runner SHALL configure the `build-helper-maven-plugin` to add `src/integration-test/java` as a test source directory and `src/integration-test/resources` as a test resource directory when the `integration-test` profile is active.
4. THE Integration_Test_Runner SHALL pass the Ranger_Admin URL as a system property (`ranger.admin.url`) to integration tests, defaulting to `http://localhost:6080`.
5. WHILE the `integration-test` profile is inactive, THE Integration_Test_Runner SHALL skip all integration test compilation and execution.

### Requirement 5: Ranger Admin Health Verification Integration Test

**User Story:** As a developer, I want a smoke-test integration test that verifies Ranger Admin is reachable and responsive, so that I have confidence the test environment is correctly provisioned before running further integration tests.

#### Acceptance Criteria

1. THE Integration_Test_Runner SHALL include a Java integration test class (`RangerAdminHealthIT`) in `src/integration-test/java`.
2. WHEN executed, THE RangerAdminHealthIT SHALL send an HTTP GET request to the Ranger_Admin login page endpoint and verify a successful HTTP response (status code 200).
3. WHEN executed, THE RangerAdminHealthIT SHALL send an HTTP GET request to the Ranger_Admin public API endpoint (`/service/public/v2/api/servicedef`) and verify a successful HTTP response (status code 200).
4. IF Ranger_Admin is unreachable, THEN THE RangerAdminHealthIT SHALL fail with a descriptive error message including the attempted URL and the connection error.

### Requirement 6: Service Definition Installation Integration Test

**User Story:** As a developer, I want an integration test that installs the Lake Formation service definition into a live Ranger Admin instance, so that I can verify the service definition registration works end-to-end.

#### Acceptance Criteria

1. THE Integration_Test_Runner SHALL include a Java integration test class (`ServiceDefInstallIT`) in `src/integration-test/java`.
2. WHEN executed, THE ServiceDefInstallIT SHALL POST the `ranger-servicedef-lakeformation.json` to the Ranger_Admin REST API (`/service/public/v2/api/servicedef`).
3. WHEN the service definition is successfully created, THE ServiceDefInstallIT SHALL verify the response contains the service definition name `lakeformation`.
4. WHEN the service definition already exists, THE ServiceDefInstallIT SHALL update the existing definition via PUT and verify the update succeeds.
5. IF the Ranger_Admin REST API returns an error status code, THEN THE ServiceDefInstallIT SHALL fail with a descriptive error message including the HTTP status code and response body.

### Requirement 7: CI Pipeline Integration

**User Story:** As a developer, I want the integration test infrastructure to work in CI pipelines, so that integration tests run automatically on pull requests and merges.

#### Acceptance Criteria

1. THE CI_Pipeline SHALL include a workflow step that executes the Startup_Script before running integration tests.
2. THE CI_Pipeline SHALL include a workflow step that executes the Teardown_Script after integration tests complete, regardless of test outcome.
3. THE CI_Pipeline SHALL provide a sample GitHub Actions workflow file (`.github/workflows/integration-tests.yml`) demonstrating the full lifecycle: build, start Ranger_Stack, run integration tests, stop Ranger_Stack.
4. WHEN Docker is not available in the CI environment, THE CI_Pipeline workflow SHALL skip integration tests and log a warning message.

### Requirement 8: EKS Fargate Deployment Option

**User Story:** As a developer, I want Kubernetes manifests for deploying the Ranger stack on EKS Fargate, so that the team has a cloud-native deployment option for shared or long-running test environments.

#### Acceptance Criteria

1. THE EKS_Fargate_Deployment SHALL provide Kubernetes manifests (Deployment, Service, ConfigMap) for Ranger_DB, Ranger_Solr, and Ranger_Admin.
2. THE EKS_Fargate_Deployment SHALL define a Kubernetes namespace `ranger-integration` for resource isolation.
3. THE EKS_Fargate_Deployment SHALL configure liveness and readiness probes for each pod matching the Health_Check_Probe definitions.
4. THE EKS_Fargate_Deployment SHALL expose Ranger_Admin via a Kubernetes Service of type `LoadBalancer` or `ClusterIP` with port 6080.
5. THE EKS_Fargate_Deployment SHALL include a README documenting prerequisites (EKS cluster, Fargate profile, kubectl access) and deployment commands.
6. THE EKS_Fargate_Deployment manifests SHALL use the same configurable Ranger_Version image tag as the Docker Compose definition.

### Requirement 9: EC2 Deployment Option

**User Story:** As a developer, I want documentation and scripts for deploying the Ranger stack on an EC2 instance, so that the team has an option for persistent test environments without Kubernetes overhead.

#### Acceptance Criteria

1. THE EC2_Deployment SHALL provide a shell script that installs Docker and Docker Compose on an Amazon Linux 2 or Ubuntu EC2 instance.
2. THE EC2_Deployment SHALL reuse the same `docker-compose.yml` file defined in Requirement 2 for container orchestration on the EC2 instance.
3. THE EC2_Deployment SHALL provide a script that copies the Docker Compose file and startup/teardown scripts to the EC2 instance via SCP.
4. THE EC2_Deployment SHALL include a README documenting the required EC2 instance type, security group rules (ports 6080, 8983), and IAM permissions.
5. WHEN the EC2 instance is terminated, THE EC2_Deployment documentation SHALL note that all Ranger data is lost unless persistent EBS volumes are configured.

### Requirement 10: Project Directory Structure

**User Story:** As a developer, I want a well-organized directory structure for integration test infrastructure, so that test code, configuration, and deployment artifacts are easy to find and maintain.

#### Acceptance Criteria

1. THE Integration_Test_Runner SHALL organize integration test infrastructure under the following directory structure:
   - `integration-test/docker/` for Docker Compose files and related configuration
   - `integration-test/scripts/` for startup, teardown, and utility scripts
   - `integration-test/k8s/` for Kubernetes manifests (EKS Fargate option)
   - `integration-test/ec2/` for EC2 deployment scripts and documentation
   - `src/integration-test/java/` for Java integration test source code
   - `src/integration-test/resources/` for integration test resource files
2. THE Integration_Test_Runner SHALL include a `README.md` in the `integration-test/` directory documenting the purpose of each subdirectory and quickstart instructions.
