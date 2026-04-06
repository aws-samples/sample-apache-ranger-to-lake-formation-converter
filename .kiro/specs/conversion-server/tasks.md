# Implementation Plan: Conversion Server

## Overview

Wrap the existing Ranger-to-Lake Formation sync pipeline in a long-running container process. Implementation proceeds bottom-up: data models and config first, then the run-loop and metrics, then logging configuration, and finally Docker packaging. Each step builds on the previous and ends with wiring into the main entry point.

## Tasks

- [x] 1. Add CloudWatch dependency and create data model classes
  - [x] 1.1 Add AWS SDK CloudWatch dependency to pom.xml
    - Add `software.amazon.awssdk:cloudwatch` dependency (managed by existing BOM) to the `<dependencies>` section
    - Add `logback-contrib` Jackson JSON layout dependency for structured logging: `ch.qos.logback.contrib:logback-json-classic:0.1.5` and `ch.qos.logback.contrib:logback-jackson:0.1.5`
    - _Requirements: 9.4_

  - [x] 1.2 Create `ServerConfig` POJO
    - Create `src/main/java/com/amazonaws/policyconverters/server/ServerConfig.java`
    - Fields: `shutdownTimeoutSeconds` (int, default 30), `logLevel` (String, default "INFO"), `metricsNamespace` (String, default "RangerLFSync")
    - Use `@JsonCreator` / `@JsonProperty` annotations matching the existing `AwsConfig` pattern
    - Implement `equals`, `hashCode`, `toString`
    - Validate `logLevel` against allowed values: TRACE, DEBUG, INFO, WARN, ERROR
    - _Requirements: 3.1_

  - [x] 1.3 Create `SyncCycleResult` value object
    - Create `src/main/java/com/amazonaws/policyconverters/server/SyncCycleResult.java`
    - Fields: `success` (boolean), `durationMs` (long), `policiesProcessed` (int), `grantsApplied` (int), `revocationsApplied` (int), `policiesSkipped` (int), `errorClass` (String, null on success), `errorMessage` (String, null on success), `error` (Throwable, null on success)
    - Provide static factory methods: `success(durationMs, policiesProcessed, grantsApplied, revocationsApplied, policiesSkipped)` and `failure(durationMs, Throwable)`
    - _Requirements: 5.2, 5.3, 6.2, 6.3_

  - [x] 1.4 Write property test for ServerConfig round-trip parsing
    - **Property 2: ServerConfig round-trip parsing**
    - Generate random valid `ServerConfig` objects (random int > 0, random log level from {TRACE, DEBUG, INFO, WARN, ERROR}, random alphanumeric namespace), serialize to YAML, deserialize, verify equality. Verify defaults when fields are omitted.
    - **Validates: Requirements 3.1**

- [x] 2. Implement ServerConfigLoader with environment variable overrides
  - [x] 2.1 Create `ServerConfigLoader` class
    - Create `src/main/java/com/amazonaws/policyconverters/server/ServerConfigLoader.java`
    - Extend or compose with existing `ConfigLoader` to parse the additional `server` YAML section into `ServerConfig`
    - Return a composite object containing both `SyncConfig` and `ServerConfig`
    - Apply `SERVER_SHUTDOWN_TIMEOUT_SECONDS`, `SERVER_LOG_LEVEL`, `SERVER_METRICS_NAMESPACE` environment variable overrides (env wins over file)
    - Reuse the existing `ConfigLoader.EnvironmentProvider` interface for testability
    - Handle missing `server` section by returning defaults (30, INFO, RangerLFSync)
    - Handle missing/unreadable config file by throwing with descriptive message
    - _Requirements: 3.1, 3.2, 3.3, 1.2, 1.3_

  - [x] 2.2 Write property test for environment variable override precedence
    - **Property 3: Environment variable override precedence**
    - Generate random config file values and random env var values for each server field, verify env var value is always used when set.
    - **Validates: Requirements 3.2, 3.3**

  - [x] 2.3 Write property test for invalid configuration error exit
    - **Property 1: Invalid configuration produces error exit**
    - Generate random `SyncConfig` with randomly nulled-out required fields (rangerAdminUrl, region, etc.), verify that validation produces errors and startup would fail with non-zero exit.
    - **Validates: Requirements 1.2, 1.3**

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement MetricsEmitter for CloudWatch publishing
  - [x] 4.1 Create `MetricsEmitter` class
    - Create `src/main/java/com/amazonaws/policyconverters/server/MetricsEmitter.java`
    - Constructor takes `CloudWatchClient` and `ServerConfig` (for namespace)
    - `recordSuccess(SyncCycleResult)`: publishes `SyncCycleSuccess=1`, `SyncCycleDuration=durationMs`, `PoliciesProcessed`, `GrantsApplied`, `RevocationsApplied` metrics
    - `recordFailure(SyncCycleResult)`: publishes `SyncCycleFailure=1`, `ErrorCount=1` with `ErrorType` dimension, plus `PoliciesProcessed`, `GrantsApplied`, `RevocationsApplied`
    - All metrics include `ServiceName=conversion-server` dimension
    - Use configurable namespace (default `RangerLFSync`)
    - Buffer all metric data into a single `PutMetricData` call per cycle
    - Catch and log CloudWatch errors without failing the cycle
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

  - [x] 4.2 Write property test for metrics correctness per cycle result
    - **Property 7: Metrics correctness per cycle result**
    - Generate random `SyncCycleResult` (success/failure), call `recordSuccess`/`recordFailure`, capture `PutMetricData` requests via mock `CloudWatchClient`, verify correct metric names, values, and dimensions.
    - **Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6**

  - [x] 4.3 Write property test for ServiceName dimension invariant
    - **Property 8: ServiceName dimension invariant**
    - Generate random `SyncCycleResult`, verify every `MetricDatum` in the captured `PutMetricData` request includes `ServiceName=conversion-server`.
    - **Validates: Requirements 6.7**

  - [x] 4.4 Write property test for metrics namespace configurability
    - **Property 9: Metrics namespace configurability**
    - Generate random non-empty namespace strings, create `MetricsEmitter` with that namespace, verify `PutMetricData` calls use the configured namespace.
    - **Validates: Requirements 6.1**

- [x] 5. Implement ServerLifecycle run-loop and graceful shutdown
  - [x] 5.1 Create `ServerLifecycle` class
    - Create `src/main/java/com/amazonaws/policyconverters/server/ServerLifecycle.java`
    - Constructor takes `SyncService`, `MetricsEmitter`, `ServerConfig`, `SyncConfig`
    - `run()`: implements `while(running) { executeCycle(); sleep(policyRefreshIntervalMs); }` loop
    - `executeCycle()`: calls into `SyncService`, measures duration, builds `SyncCycleResult`, logs cycle start/completion/failure, emits metrics
    - `shutdown()`: sets `volatile boolean running = false`, waits for current cycle via `CountDownLatch` with `shutdownTimeoutSeconds` timeout
    - Maintain a cycle sequence counter (AtomicLong)
    - Log cycle start at INFO: cycle number, start timestamp
    - Log cycle success at INFO: cycle number, duration, policies fetched, grants applied, revocations applied, policies skipped
    - Log cycle failure at ERROR: cycle number, error class, error message, stack trace
    - Log unmapped principals at WARN (delegate to existing logging)
    - Log Ranger Admin connection at DEBUG: URL, policy count
    - On shutdown timeout, log WARN and return (caller exits with code 1)
    - On successful shutdown, return normally (caller exits with code 0)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 5.2 Write property test for graceful shutdown waits for current cycle
    - **Property 10: Graceful shutdown waits for current cycle**
    - Generate random cycle durations less than the configured timeout, verify the cycle completes before `shutdown()` returns. Use a mock `SyncService` with configurable delay.
    - **Validates: Requirements 2.4**

  - [x] 5.3 Write property test for sleep interval matches configuration
    - **Property 11: Sleep interval matches configuration**
    - Generate random `policyRefreshIntervalMs` values, run a single cycle, measure actual sleep duration, verify it matches within ±50ms tolerance.
    - **Validates: Requirements 2.2**

  - [x] 5.4 Write property test for cycle result log completeness
    - **Property 6: Cycle result log completeness**
    - Generate random `SyncCycleResult` (success/failure), execute a cycle, capture log output, verify success logs contain cycle number, duration, all counts; failure logs contain cycle number, error class, error message, stack trace.
    - **Validates: Requirements 5.2, 5.3**

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Create ConversionServerMain entry point
  - [x] 7.1 Create `ConversionServerMain` class
    - Create `src/main/java/com/amazonaws/policyconverters/server/ConversionServerMain.java`
    - `main(String[] args)`: parse CLI args (expect config file path), load config via `ServerConfigLoader`, validate via existing `ConfigValidator`, set Logback log level from `ServerConfig.logLevel`, create `CloudWatchClient`, create `MetricsEmitter`, wire `SyncService` (reuse wiring logic from `SyncServiceMain`), create `ServerLifecycle`, register SIGTERM shutdown hook, log startup info (version, sync interval, log level), call `serverLifecycle.run()`
    - On missing args: log error to stderr, exit with code 1
    - On config load/validation failure: log errors to stderr, exit with code 1 within 5 seconds
    - On successful shutdown: exit with code 0
    - On shutdown timeout: exit with code 1
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.3, 2.5, 2.6, 8.1, 8.2_

  - [x] 7.2 Update JAR manifest to declare `ConversionServerMain` as main class
    - Update `maven-assembly-plugin` or `maven-jar-plugin` configuration in `pom.xml` to set `mainClass` to `com.amazonaws.policyconverters.server.ConversionServerMain`
    - Verify the fat JAR can be started with `java -jar <jarfile> <config-path>`
    - _Requirements: 9.1, 9.2, 9.3_

- [x] 8. Configure structured JSON logging
  - [x] 8.1 Create Logback JSON configuration
    - Create `src/main/resources/logback.xml` with JSON layout for stdout (all levels) and stderr (ERROR only)
    - Use `logback-contrib` `JacksonJsonLayout` to produce structured JSON with fields: `timestamp` (ISO-8601), `level`, `logger`, `message`, `thread`
    - Configure stdout appender: `ConsoleAppender` targeting `System.out` with JSON layout
    - Configure stderr appender: `ConsoleAppender` targeting `System.err` with `ThresholdFilter` at ERROR level and JSON layout
    - Set root logger level to `INFO` (overridden at runtime by `ServerConfig.logLevel`)
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [x] 8.2 Write property test for structured JSON log format
    - **Property 4: Structured JSON log format**
    - Generate random log messages at random levels, capture stdout output, parse each line as JSON, verify presence of `timestamp`, `level`, `logger`, `message`, `thread` fields. Verify `timestamp` is ISO-8601 format.
    - **Validates: Requirements 4.1**

  - [x] 8.3 Write property test for error log routing to stderr
    - **Property 5: Error log routing to stderr**
    - Generate random log messages at random levels, capture both stdout and stderr, verify ERROR messages appear on stderr, non-ERROR messages do not appear on stderr.
    - **Validates: Requirements 4.2**

- [x] 9. Create server configuration file and Dockerfile
  - [x] 9.1 Create sample `conf/server-config.yaml`
    - Create a sample configuration file with all sections: `rangerConfig`, `awsConfig`, `principalMapping`, sync settings, and the new `server` section
    - Include comments explaining each field and its default
    - _Requirements: 3.1_

  - [x] 9.2 Create `Dockerfile` in project root
    - Stage 1 (build): `maven:3.9-eclipse-temurin-17`, copy `pom.xml`, `src/`, `conf/`, run `mvn clean package -DskipTests`
    - Stage 2 (runtime): `eclipse-temurin:17-jre-alpine`, copy fat JAR and `conf/server-config.yaml`
    - Set `STOPSIGNAL SIGTERM`
    - Add `HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD pgrep -f "java.*app.jar" || exit 1`
    - Set `ENTRYPOINT ["java", "-jar", "app.jar", "/app/config.yaml"]`
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- All new classes go under `com.amazonaws.policyconverters.server` package
- Existing conversion classes are reused without modification
