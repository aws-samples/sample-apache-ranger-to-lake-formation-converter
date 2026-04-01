# Implementation Plan: Ranger Integration Test Infrastructure

## Overview

This plan implements the integration test infrastructure for the Ranger Lake Formation Sync Plugin. It creates Docker Compose definitions, lifecycle scripts, Maven integration-test profile, Java integration tests, service definition installation, and documentation. CI/CD, EKS Fargate, and EC2 deployment are deferred to future phases. Each task builds incrementally so that the Ranger stack can be validated at each step.

## Tasks

- [x] 1. Create project directory structure and Docker Compose file
  - [x] 1.1 Create directory scaffolding
    - Create directories: `integration-test/docker/`, `integration-test/scripts/`, `integration-test/k8s/`, `integration-test/ec2/`, `src/integration-test/java/org/apache/ranger/lakeformation/it/`, `src/integration-test/resources/`
    - _Requirements: 10.1_

  - [x] 1.2 Create Docker Compose file
    - Create `integration-test/docker/docker-compose.yml` with three services: `ranger-db` (PostgreSQL), `ranger-solr` (Solr), `ranger-admin`
    - Use pre-built Docker Hub images `apache/ranger-db`, `apache/ranger-solr`, `apache/ranger` with `${RANGER_VERSION:-2.4.0}` tag
    - Define `rangernw` bridge network, expose ports 6080 (admin) and 8983 (solr)
    - Configure `depends_on` so ranger-db and ranger-solr start before ranger-admin
    - Add health checks: `pg_isready` for DB, HTTP check for Solr, HTTP check for Admin `/login.jsp`
    - Set environment variables: `POSTGRES_PASSWORD`, `RANGER_DB_USER`, `RANGER_DB_PASSWORD`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

- [x] 2. Create startup and teardown scripts
  - [x] 2.1 Create startup script
    - Create `integration-test/scripts/start-ranger.sh` with `--timeout`, `--interval`, `--compose-file` flags
    - Default timeout=120s, interval=5s, compose-file=`../docker/docker-compose.yml`
    - Run `docker compose -f <file> up -d`
    - Poll `http://localhost:6080/login.jsp` via `curl -sf` in a loop
    - On success: print readiness message with URL, exit 0
    - On timeout: print error, dump `docker compose logs`, exit 1
    - Use `set -e` for early exit on unexpected errors, but handle poll loop explicitly
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 2.2 Create teardown script
    - Create `integration-test/scripts/stop-ranger.sh` with `--compose-file` flag
    - Run `docker compose -f <file> down -v --remove-orphans`
    - Always exit 0 regardless of Docker Compose exit code (idempotent teardown)
    - _Requirements: 3.5, 3.6_

- [x] 3. Configure Maven integration-test profile
  - [x] 3.1 Add integration-test profile to pom.xml
    - Add `<profile>` with id `integration-test`
    - Configure `build-helper-maven-plugin` to add `src/integration-test/java` as test source and `src/integration-test/resources` as test resource
    - Configure `maven-failsafe-plugin` to run `*IT.java` classes
    - Pass `-Dranger.admin.url=http://localhost:6080` as system property to failsafe
    - Configure `maven-compiler-plugin` for Java 8 compilation of integration test sources
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [x] 4. Checkpoint - Verify infrastructure setup
  - Ensure Docker Compose file is valid YAML, scripts are executable, and Maven profile compiles. Ask the user if questions arise.

- [x] 5. Implement RangerAdminHealthIT integration test
  - [x] 5.1 Create RangerAdminHealthIT class
    - Create `src/integration-test/java/org/apache/ranger/lakeformation/it/RangerAdminHealthIT.java`
    - Read `ranger.admin.url` from system property with default `http://localhost:6080`
    - Implement `testLoginPageReachable()`: HTTP GET to `/login.jsp`, assert status 200
    - Implement `testServiceDefApiReachable()`: HTTP GET to `/service/public/v2/api/servicedef`, assert status 200
    - Use `java.net.HttpURLConnection` for HTTP requests (no new dependencies)
    - On connection failure, throw `AssertionError` with attempted URL and original exception message
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [ ]* 5.2 Write property test for health check error messages
    - **Property 1: Health check error messages include the attempted URL**
    - Create property test in `src/test/java` using jqwik
    - For any valid URL string, when connection fails, verify the error message contains the attempted URL
    - Minimum 100 iterations
    - **Validates: Requirements 5.4**

- [x] 6. Implement ServiceDefInstallIT integration test
  - [x] 6.1 Create ServiceDefInstallIT class
    - Create `src/integration-test/java/org/apache/ranger/lakeformation/it/ServiceDefInstallIT.java`
    - Read `ranger.admin.url` from system property with default `http://localhost:6080`
    - Load `ranger-servicedef-lakeformation.json` from classpath
    - Implement `testCreateServiceDef()`: POST JSON to `/service/public/v2/api/servicedef`, assert status 200, verify response body contains `"lakeformation"`
    - Implement `testUpdateServiceDef()`: If servicedef already exists (409), PUT to `/service/public/v2/api/servicedef/{id}`, assert status 200
    - Implement `testErrorHandling()`: Verify descriptive error message on bad request including HTTP status code and response body
    - Use `java.net.HttpURLConnection` with Basic auth (`admin`/`rangerR0cks!`)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [ ]* 6.2 Write property test for service definition install error messages
    - **Property 2: Service definition install error messages include HTTP status code and response body**
    - Create property test in `src/test/java` using jqwik
    - For any HTTP error status code (4xx/5xx) and any non-empty response body, verify the error message contains both the numeric status code and the response body text
    - Minimum 100 iterations
    - **Validates: Requirements 6.5**

- [x] 7. Checkpoint - Verify integration tests compile
  - Ensure integration test classes compile under the `integration-test` profile and unit/property tests pass. Ask the user if questions arise.

- [x] 8. Install Lake Formation service definition into Ranger
  - [x] 8.1 Add service definition installation to the integration test lifecycle
    - Update `run-integration-tests.sh` to install the Lake Formation service definition into Ranger Admin after the stack is healthy and before running integration tests
    - Use `curl` to POST `conf/ranger-servicedef-lakeformation.json` to `http://localhost:6080/service/public/v2/api/servicedef` with Basic auth (`admin`/`rangerR0cks!`)
    - Handle the case where the servicedef already exists (HTTP 409) by performing a PUT update instead
    - Log success/failure of the installation
    - _Requirements: 6.2, 6.3, 6.4_

- [x] 9. Create documentation
  - [x] 11.1 Create deployment strategy comparison matrix
    - Create `integration-test/DEPLOYMENT-STRATEGIES.md` with comparison matrix covering: Docker Compose, EKS Fargate, EC2, Testcontainers
    - Assess dimensions: setup complexity, CI/CD compatibility, cost, startup time, teardown reliability, local dev experience
    - Include recommended strategy with rationale
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 11.2 Create integration test README
    - Create `integration-test/README.md` documenting purpose of each subdirectory and quickstart instructions
    - Include how to run locally: `./integration-test/scripts/start-ranger.sh && mvn verify -Pintegration-test && ./integration-test/scripts/stop-ranger.sh`
    - _Requirements: 10.2_

- [x] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, Maven profile compiles integration tests, and all documentation is in place. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Integration tests use `java.net.HttpURLConnection` to avoid adding new dependencies
- All shell scripts should be created with executable permissions (`chmod +x`)
