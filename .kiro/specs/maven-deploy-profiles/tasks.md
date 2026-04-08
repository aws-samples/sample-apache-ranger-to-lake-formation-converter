# Implementation Plan: Maven Deploy Profiles

## Overview

Split the monolithic `deploy/docker-compose.yml` into two independent compose files, create Maven-filtered template files for config generation, add two Maven profiles (`deploy-server` and `deploy-ranger-admin`) to `pom.xml`, and implement a utility class for principal mapping parsing with property-based tests.

## Tasks

- [x] 1. Create split Docker Compose files and update deploy directory
  - [x] 1.1 Create `deploy/docker-compose-server.yml` with only the conversion-server service
    - Extract the conversion-server service from `deploy/docker-compose.yml`
    - Declare `rangernw` as an external network
    - _Requirements: 3.1, 3.3_
  - [x] 1.2 Create `deploy/docker-compose-ranger-admin.yml` with Ranger Admin stack services
    - Extract ranger-db, ranger-solr, ranger-admin services from `deploy/docker-compose.yml`
    - Declare `rangernw` as an external network
    - _Requirements: 3.2, 3.4_
  - [x] 1.3 Remove the old `deploy/docker-compose.yml`
    - The monolithic file is replaced by the two new compose files
    - _Requirements: 3.1, 3.2_

- [x] 2. Create template files in `src/main/deploy/`
  - [x] 2.1 Create `src/main/deploy/server-config-deploy.yaml` template
    - Use `${...}` Maven property placeholders for all configurable fields
    - Include `${principal.user.mappings.yaml}`, `${principal.group.mappings.yaml}`, `${principal.role.mappings.yaml}` for Groovy-generated YAML fragments
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_
  - [x] 2.2 Create `src/main/deploy/.env` template
    - Use `${...}` Maven property placeholders for AWS credentials and Ranger password
    - _Requirements: 5.1, 5.2_
  - [x] 2.3 Remove the old hand-edited `deploy/server-config-deploy.yaml` and `deploy/.env`
    - These are now generated from templates; keeping them causes confusion
    - _Requirements: 4.1, 5.3_

- [x] 3. Implement principal mapping parser utility
  - [x] 3.1 Create `DeployTemplateUtils.java` in `src/main/java/com/amazonaws/policyconverters/deploy/`
    - Implement a static `parseMappingsToYaml(String csv)` method that converts comma-separated `name=arn` pairs into indented YAML map entries
    - Return `    {}` for empty/blank input
    - Throw `IllegalArgumentException` if a pair is missing the `=` separator
    - This mirrors the Groovy script logic so it can be tested in Java
    - _Requirements: 1.5, 4.4_
  - [ ]* 3.2 Write property test for principal mapping parsing (Property 3)
    - **Property 3: Principal mapping parsing preserves all entries**
    - Use jqwik `@Property` with `@ForAll` generators for lists of `(name, arn)` pairs
    - Convert to comma-separated string, run through `parseMappingsToYaml`, parse resulting YAML, assert map equals input
    - Minimum 100 iterations
    - **Validates: Requirements 1.5, 4.4**
  - [ ]* 3.3 Write unit tests for `DeployTemplateUtils`
    - Test empty input produces `{}`
    - Test single mapping `alice=arn:aws:iam::123:user/Alice`
    - Test multiple mappings
    - Test missing `=` throws exception
    - _Requirements: 1.5, 4.4_

- [x] 4. Implement template filtering utility and round-trip tests
  - [x] 4.1 Create `TemplateFilter.java` in `src/main/java/com/amazonaws/policyconverters/deploy/`
    - Implement a static `filter(String template, Map<String, String> properties)` method that replaces `${key}` tokens with property values
    - This mirrors `maven-resources-plugin` filtering for testability
    - _Requirements: 1.7, 1.8, 4.1, 5.1_
  - [ ]* 4.2 Write property test for config YAML round-trip (Property 1)
    - **Property 1: Config YAML generation round-trip**
    - Use jqwik `@Property` with `@ForAll` generators for AWS region, account ID, role ARN, Ranger URL, password, principal mappings, and optional sync/server settings
    - Filter the `server-config-deploy.yaml` template, deserialize with Jackson `ObjectMapper(YAMLFactory)` into `SyncConfig`, assert all fields match inputs
    - Minimum 100 iterations
    - **Validates: Requirements 1.7, 4.1, 4.2, 4.3, 4.4, 4.6**
  - [ ]* 4.3 Write property test for .env round-trip (Property 2)
    - **Property 2: Env file generation round-trip**
    - Use jqwik `@Property` with `@ForAll` generators for region, access key, secret key, password
    - Filter the `.env` template, parse as `KEY=VALUE` lines, assert each value matches input
    - Minimum 100 iterations
    - **Validates: Requirements 1.8, 5.1, 5.2**

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Add Maven profiles to `pom.xml`
  - [x] 6.1 Add the `deploy-server` profile to `pom.xml`
    - Define default properties (ranger.admin.url, ranger.admin.password, aws.region, ranger.version, sync/server defaults, empty principal mappings)
    - Add `maven-enforcer-plugin` execution at `validate` phase to require `aws.account.id`, `aws.role.arn`, `aws.access.key.id`, `aws.secret.access.key`
    - Add `gmavenplus-plugin` execution at `initialize` phase to parse principal mappings into YAML
    - Add `maven-resources-plugin` execution at `generate-resources` phase to filter templates from `src/main/deploy/` into `deploy/`
    - Add `exec-maven-plugin` executions: `ensure-network` (docker network create rangernw), `docker-compose-up` (docker compose -f docker-compose-server.yml up --build -d), `docker-compose-down` (docker compose -f docker-compose-server.yml down)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 6.1, 6.2, 6.3, 6.4, 6.5, 7.1_
  - [x] 6.2 Add the `deploy-ranger-admin` profile to `pom.xml`
    - Define default properties (ranger.admin.password, ranger.version)
    - Add `exec-maven-plugin` executions: `ensure-network`, `docker-compose-up-ranger` (docker compose -f docker-compose-ranger-admin.yml up -d), `docker-compose-down` (docker compose -f docker-compose-ranger-admin.yml down)
    - Pass `RANGER_VERSION` environment variable to docker compose
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 7.2_

- [x] 7. Update `deploy/README.md` with new Maven profile usage
  - Document `mvn generate-resources exec:exec@docker-compose-up -Pdeploy-server -Daws.account.id=... ...` command
  - Document `mvn exec:exec@docker-compose-down -Pdeploy-server` command
  - Document `mvn generate-resources exec:exec@docker-compose-up -Pdeploy-ranger-admin` command
  - Document `mvn exec:exec@docker-compose-down -Pdeploy-ranger-admin` command
  - Document combined usage (both profiles together)
  - List all Maven properties with defaults
  - _Requirements: 1.1, 2.1, 7.1, 7.2, 7.3_

- [x] 8. Wire everything together and add `.gitignore` entries
  - [x] 8.1 Add `deploy/server-config-deploy.yaml` and `deploy/.env` to `.gitignore`
    - These are now generated files and should not be committed
    - _Requirements: 5.3, 4.1_
  - [x] 8.2 Ensure `src/main/deploy/` templates are tracked in git
    - Templates are source files and must be committed
    - _Requirements: 4.1, 5.1_

- [x] 9. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests validate universal correctness properties from the design document
- The Java utility classes (`DeployTemplateUtils`, `TemplateFilter`) mirror the Maven plugin behavior so correctness properties can be tested as unit tests without invoking Maven
- The Groovy script in the `gmavenplus-plugin` should use the same logic as `DeployTemplateUtils.parseMappingsToYaml`
