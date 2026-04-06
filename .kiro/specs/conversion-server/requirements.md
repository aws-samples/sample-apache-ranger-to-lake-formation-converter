# Requirements Document

## Introduction

This feature wraps the existing Ranger-to-Lake Formation policy conversion logic in a long-running container process deployed on EKS Fargate. Today the sync pipeline runs as a standalone Java main class (`SyncServiceMain`) that starts, polls Ranger Admin, and applies changes. The conversion server packages this as a Docker container that continuously runs the sync pipeline, emits structured logs to stdout/stderr for container log collection, publishes CloudWatch metrics for monitoring and alerting, and handles graceful shutdown on SIGTERM. No HTTP server or ports are exposed. The server code lives in `src/main/java/com/amazonaws/policyconverters/server/`.

## Glossary

- **Conversion_Server**: The long-running container process that wraps the existing policy conversion pipeline and continuously executes Sync_Cycles. No HTTP endpoints are exposed.
- **Sync_Service**: The existing `SyncService` class that computes diffs between Ranger policy snapshots and applies grant/revoke operations to Lake Formation.
- **Sync_Cycle**: A single iteration of polling Ranger Admin for policies, computing the diff, and applying changes to Lake Formation.
- **Server_Configuration**: The YAML or properties-based configuration file that includes sync settings, logging settings, and CloudWatch metrics settings.
- **Graceful_Shutdown**: An orderly shutdown sequence where the Conversion_Server finishes the current Sync_Cycle and then terminates in response to a SIGTERM signal.
- **Structured_Logger**: The JSON-formatted logging subsystem that writes all log entries to stdout/stderr for collection by container log agents (e.g., Fluent Bit to CloudWatch Logs).
- **Metrics_Emitter**: The component responsible for publishing operational metrics to a custom CloudWatch namespace.
- **Container_Health_Check**: A Docker HEALTHCHECK directive that verifies the Conversion_Server process is alive and functioning, without using HTTP endpoints.

## Requirements

### Requirement 1: Process Startup and Initialization

**User Story:** As an operator, I want to start the conversion server from a configuration file, so that the process initializes the sync pipeline and begins running Sync_Cycles continuously.

#### Acceptance Criteria

1. WHEN a valid configuration file path is provided as a command-line argument, THE Conversion_Server SHALL load the configuration, initialize the Sync_Service, and begin executing Sync_Cycles.
2. WHEN the configuration file is missing or unreadable, THE Conversion_Server SHALL log a descriptive error message to stderr and exit with a non-zero exit code within 5 seconds.
3. WHEN the configuration file contains invalid or incomplete values, THE Conversion_Server SHALL log each validation error to stderr and exit with a non-zero exit code within 5 seconds.
4. WHEN the Conversion_Server starts successfully, THE Conversion_Server SHALL log the application version, configured sync interval, and log level to stdout.

### Requirement 2: Long-Running Process Lifecycle

**User Story:** As an operator, I want the conversion server to run as a long-lived container process that continuously executes sync cycles, so that Ranger policy changes are continuously applied to Lake Formation.

#### Acceptance Criteria

1. THE Conversion_Server SHALL run as a foreground process that continuously executes Sync_Cycles at the configured poll interval.
2. WHILE the Conversion_Server is running, THE Conversion_Server SHALL sleep for the configured `policyRefreshIntervalMs` between Sync_Cycles.
3. WHEN a SIGTERM signal is received, THE Conversion_Server SHALL initiate Graceful_Shutdown.
4. WHILE Graceful_Shutdown is in progress, THE Conversion_Server SHALL wait for the current Sync_Cycle to complete before terminating.
5. IF Graceful_Shutdown does not complete within a configurable timeout (default: 30 seconds), THEN THE Conversion_Server SHALL force termination and log a warning to stdout.
6. WHEN Graceful_Shutdown completes successfully, THE Conversion_Server SHALL exit with exit code 0.

### Requirement 3: Server Configuration

**User Story:** As an operator, I want to configure process-level settings (log level, shutdown timeout, metrics namespace) alongside the existing sync configuration, so that I have a single configuration file for the entire process.

#### Acceptance Criteria

1. THE Server_Configuration SHALL support a `server` section containing: `shutdownTimeoutSeconds` (integer, default 30), `logLevel` (string, one of TRACE/DEBUG/INFO/WARN/ERROR, default INFO), and `metricsNamespace` (string, default `RangerLFSync`).
2. THE Server_Configuration SHALL support environment variable overrides for all server settings using the prefix `SERVER_` (e.g., `SERVER_SHUTDOWN_TIMEOUT_SECONDS`, `SERVER_LOG_LEVEL`, `SERVER_METRICS_NAMESPACE`).
3. WHEN an environment variable override is set, THE Conversion_Server SHALL use the environment variable value in preference to the configuration file value.

### Requirement 4: Structured Logging

**User Story:** As an operator, I want the server to write structured JSON logs to stdout/stderr, so that container log agents can collect and forward logs to CloudWatch Logs for monitoring and troubleshooting.

#### Acceptance Criteria

1. THE Conversion_Server SHALL write all application log entries to stdout in structured JSON format with fields: `timestamp` (ISO-8601), `level`, `logger`, `message`, and `thread`.
2. THE Conversion_Server SHALL write error-level log entries to stderr in addition to stdout.
3. THE Conversion_Server SHALL support configurable log levels (TRACE, DEBUG, INFO, WARN, ERROR) via the `server.logLevel` configuration or the `SERVER_LOG_LEVEL` environment variable.
4. WHEN the log level is changed via configuration, THE Conversion_Server SHALL apply the new log level at startup without requiring a code change.

### Requirement 5: Sync Cycle Logging

**User Story:** As an operator, I want detailed logging of each sync cycle, so that I can debug issues by reviewing what was found, what was applied, and what errors occurred.

#### Acceptance Criteria

1. WHEN a Sync_Cycle begins, THE Structured_Logger SHALL log a message at INFO level containing the cycle sequence number and start timestamp.
2. WHEN a Sync_Cycle completes successfully, THE Structured_Logger SHALL log a message at INFO level containing: the cycle sequence number, duration in milliseconds, number of Ranger policies fetched, number of grants applied, number of revocations applied, and number of policies skipped due to gaps.
3. WHEN a Sync_Cycle encounters an error, THE Structured_Logger SHALL log a message at ERROR level containing: the cycle sequence number, the error class name, the error message, and the stack trace.
4. WHEN a principal mapping is not found for a Ranger user, group, or role, THE Structured_Logger SHALL log a message at WARN level containing the unmapped principal name and the Ranger policy ID.
5. WHEN the Conversion_Server connects to Ranger Admin at the start of a Sync_Cycle, THE Structured_Logger SHALL log a message at DEBUG level containing the Ranger Admin URL and the number of policies returned.

### Requirement 6: CloudWatch Metrics Emission

**User Story:** As an operator, I want the conversion server to publish operational metrics to CloudWatch, so that I can create dashboards and alarms for monitoring the sync pipeline.

#### Acceptance Criteria

1. THE Metrics_Emitter SHALL publish metrics to a configurable CloudWatch namespace (default: `RangerLFSync`).
2. WHEN a Sync_Cycle completes successfully, THE Metrics_Emitter SHALL publish a `SyncCycleSuccess` metric with value 1 and a `SyncCycleDuration` metric with the cycle duration in milliseconds.
3. WHEN a Sync_Cycle fails with an error, THE Metrics_Emitter SHALL publish a `SyncCycleFailure` metric with value 1.
4. WHEN a Sync_Cycle completes, THE Metrics_Emitter SHALL publish a `PoliciesProcessed` metric with the number of Ranger policies fetched in the cycle.
5. WHEN a Sync_Cycle completes, THE Metrics_Emitter SHALL publish a `GrantsApplied` metric with the number of Lake Formation grants applied and a `RevocationsApplied` metric with the number of Lake Formation revocations applied.
6. WHEN an error occurs during a Sync_Cycle, THE Metrics_Emitter SHALL publish an `ErrorCount` metric with a `ErrorType` dimension set to the error class name.
7. THE Metrics_Emitter SHALL include a `ServiceName` dimension with value `conversion-server` on all published metrics.

### Requirement 7: Docker Container Packaging

**User Story:** As a developer, I want a Dockerfile that packages the conversion server as a container image, so that it can be deployed to EKS Fargate.

#### Acceptance Criteria

1. THE Conversion_Server SHALL include a Dockerfile in the project root that builds a container image based on a Java 17 base image.
2. THE Dockerfile SHALL use a multi-stage build: the first stage builds the fat JAR using Maven, and the second stage copies the JAR into a minimal runtime image.
3. THE Dockerfile SHALL define a HEALTHCHECK instruction that verifies the Conversion_Server process is running (e.g., using `pgrep` or checking the PID file).
4. THE Dockerfile SHALL set the ENTRYPOINT to run the Conversion_Server JAR with a default configuration file path.
5. THE Dockerfile SHALL define a STOPSIGNAL of SIGTERM so that EKS Fargate sends SIGTERM for graceful shutdown.
6. WHEN the container image is built, THE Dockerfile SHALL produce an image smaller than 500 MB.

### Requirement 8: Server Code Location

**User Story:** As a developer, I want the server code to live in a dedicated package, so that it is cleanly separated from the existing conversion logic.

#### Acceptance Criteria

1. THE Conversion_Server SHALL place all server-specific classes under the package `com.amazonaws.policyconverters.server` with source files in `src/main/java/com/amazonaws/policyconverters/server/`.
2. THE Conversion_Server SHALL delegate all policy conversion logic to the existing classes in `com.amazonaws.policyconverters.ranger` and `com.amazonaws.policyconverters.lakeformation` without duplicating conversion code.

### Requirement 9: Build and Packaging

**User Story:** As a developer, I want the server to be buildable with the existing Maven project, so that I do not need a separate build process.

#### Acceptance Criteria

1. THE Conversion_Server SHALL be buildable using `mvn clean package` from the project root.
2. WHEN the build completes, THE Conversion_Server SHALL produce a fat JAR containing all dependencies required to run the server.
3. THE Conversion_Server SHALL declare a main class in the JAR manifest so that the server can be started with `java -jar <jarfile> <config-path>`.
4. THE Conversion_Server SHALL include the AWS SDK CloudWatch dependency for metrics emission.
