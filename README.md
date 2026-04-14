
> This is a work in progress and has a lot of work left. Please contact hocanint@amazon.com if you wish to contribute.

# Ranger Lake Formation Sync Plugin

A Java utility that bridges Apache Ranger access control policies to AWS Lake Formation permissions. It converts Ranger's policy model (allow/deny rules on users, groups, roles) into Lake Formation's grant/revoke permission model via an intermediate Cedar policy representation, enabling organizations to manage Lake Formation permissions through Ranger Admin.

## Features

- **Multi-Service Ranger Support**: Simultaneously manage policies from multiple Ranger service types — LakeFormation, Apache Hive, Presto, and Trino — with a unified Cedar policy repository driving Lake Formation permissions.
- **Bulk Export & Convert**: One-shot extraction of all Ranger policies via REST API, conversion to Lake Formation permissions, and batch application.
- **Real-Time Sync**: A `RangerBasePlugin` that receives policy updates from Ranger Admin and incrementally applies changes to Lake Formation.
- **Service Definition Installation**: Custom service types registered in Ranger Admin (lakeformation, hive, presto, trino), enabling policy authoring for Lake Formation resources through the Ranger UI.
- **Incremental Diff**: Computes deltas between policy snapshots rather than revoking all and re-granting, minimizing Lake Formation API calls.
- **Wildcard Expansion with Periodic Refresh**: Expands wildcard resource patterns (e.g., `db_*`) against the Glue Data Catalog. A configurable periodic refresh re-evaluates glob patterns to pick up newly created resources without waiting for a Ranger policy push.
- **Reverse Sync / Drift Detection**: Retrieves actual Lake Formation permissions via `ListPermissions`, computes drift against the Cedar-authoritative state, and applies corrective GRANT/REVOKE operations to reconcile out-of-band changes.
- **Cedar Policy Bridge**: An intermediate Cedar policy representation provides a formal, schema-validated layer between Ranger policies and Lake Formation permissions.
- **Atomic Per-Policy Application**: Permission changes for a single Ranger policy are applied as a unit with rollback on failure.
- **Checkpoint Persistence**: Sync state (Cedar policy text and per-service Ranger policy versions) is persisted to a JSON checkpoint file, surviving restarts without a full re-sync.
- **Gap Reporting**: Produces a structured JSON report of Ranger features that have no Lake Formation equivalent (data masking, deny policies, etc.).
- **Dead-Letter Log**: Failed operations that exhaust retries are written to a JSON-lines file for manual remediation.
- **Principal Mapping**: Configurable mapping from Ranger users/groups/roles to AWS IAM principal ARNs.
- **Structured Logging**: JSON-formatted logs to stdout/stderr for container log collection (e.g., Fluent Bit to CloudWatch Logs).
- **CloudWatch Metrics**: Publishes operational metrics (sync cycle success/failure, duration, grants/revocations applied, error counts) to a configurable CloudWatch namespace.
- **Dry-Run Mode**: Serializes LF operations to JSON files instead of calling AWS APIs, for testing and human review.

## Features not yet implemented

- **Tags**: Tags within Apache Ranger is not yet supported but is planned to be implemented. Due to how tagging works in both systems being vastly different, this has a lot of complexity that needs to be thoroughly thought through, implemented and tested. Also, tagging usually requires the integration of a Tagging service, such as Apache Atlas which increases the complexity.

## Features not planned to be implemented

- **Data Masking**: Data masking is not yet supported within Lake Formation. Although this can be implemented using Glue Views, it will add very significant complexity.
- **Row Filters**: We will wait for LF's RLS feature to be available before tackling this feature to be able to leverage it.
- **ABAC and Conditions**: There is no way to perform this at the moment.
- **Expire_on**: We do not support this today and we would need to implement this capability manually.

## Requirements

- Java 17 (JDK 17+)
- Apache Maven 3.6+
- Apache Ranger 2.4+ (compatible with 2.4, 2.6, 2.7, 2.8)
- AWS account with Lake Formation and Glue Data Catalog access
- IAM credentials with permissions for `lakeformation:GrantPermissions`, `lakeformation:RevokePermissions`, `lakeformation:ListPermissions`, `glue:GetDatabases`, `glue:GetTables`, `glue:GetTable`

## Building

```bash
cd ranger-lakeformation-plugin
mvn clean package
```

This produces:
- `target/ranger-lakeformation-plugin-1.0.0-SNAPSHOT.jar` — the plugin JAR
- `target/ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar` — fat JAR with all dependencies

## Running Tests

### Unit Tests

```bash
mvn test
```

Tests include JUnit 5 unit tests and jqwik property-based tests (minimum 100 iterations per property).

### Integration Tests

Integration tests come in two flavors:

**In-process pipeline tests** exercise the conversion chain against a live Docker Ranger Admin instance using a dry-run client that writes LF operations to JSON files:

```bash
# Full lifecycle: start Ranger, run tests, tear down
./integration-test/scripts/run-integration-tests.sh

# Or manually:
./integration-test/scripts/start-ranger.sh
mvn verify -Pintegration-test
./integration-test/scripts/stop-ranger.sh
```

**Containerized integration tests** run the conversion server as a Docker container alongside Ranger Admin. The server registers as a real plugin, receives policy updates via the Ranger policy refresh mechanism, and writes dry-run output to a shared volume. Tests create/update/delete policies via REST and validate the dry-run JSON files from the host:

```bash
# The Docker Compose stack includes: ranger-db, ranger-solr, ranger-admin, conversion-server
docker compose -f integration-test/docker/docker-compose.yml up --build
```

Integration tests produce human-readable JSON audit logs in `logs/it-audit-<TestClass>.json` showing each Ranger input action and the resulting Lake Formation API calls. See [DESIGN.md](DESIGN.md) for details.

### Dry-Run Mode

The sync service supports a dry-run mode that serializes LF operations to JSON files instead of calling AWS APIs. Enable via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `DRY_RUN_ENABLED` | Set to `true` to enable dry-run mode | disabled |
| `DRY_RUN_OUTPUT_DIR` | Directory for JSON output files | `./dry-run-output` |

Each sync cycle writes a `dry-run-NNN.json` file containing the timestamp, sequence number, and the list of LF permission operations that would have been applied.

## Installation

### 1. Install Service Definitions in Ranger Admin

Service definitions tell Ranger Admin about the supported service types, their resources, and access types. The plugin bundles definitions for `lakeformation`, `hive`, `presto`, and `trino`.

**Option A: REST API installation**

```bash
java -cp ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  com.amazonaws.policyconverters.app.ServiceDefInstallerMain \
  --mode rest \
  --config /path/to/sync-config.yaml
```

When `rangerServices` is configured in the YAML, the installer registers all configured service definitions. Otherwise it defaults to the `lakeformation` service definition only.

**Option B: File-based installation**

```bash
java -cp ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  com.amazonaws.policyconverters.app.ServiceDefInstallerMain \
  --mode file \
  --ranger-admin-home /opt/ranger-admin
```

This copies service definition JSON files to `ranger-admin/ews/webapp/WEB-INF/classes/ranger-plugins/<serviceType>/`. Restart Ranger Admin after file-based installation.

Both modes accept an optional `--service-def <path>` flag to use a custom service definition JSON instead of the bundled one.

### 2. Create Service Instances in Ranger Admin

After installing service definitions, create service instances in the Ranger Admin UI for each service type you want to sync. For a LakeFormation service, configure:

| Property | Description |
|----------|-------------|
| `aws.region` | AWS region (e.g., `us-east-1`) |
| `aws.catalog.id` | Glue Data Catalog ID (AWS account ID) |
| `aws.access.key` | AWS access key (optional if using IAM role) |
| `aws.secret.key` | AWS secret key (optional if using IAM role) |
| `aws.role.arn` | IAM role ARN for AssumeRole (optional) |

### 3. Deploy Plugin Configuration Files

Copy the configuration files to the plugin's configuration directory:

```
/etc/ranger/lakeformation/conf/
├── ranger-lakeformation-security.xml      # Ranger Admin connection and sync settings
├── ranger-lakeformation-audit.xml         # Audit logging configuration
├── ranger-lakeformation-policymgr-ssl.xml # SSL settings (if using HTTPS)
└── principal-mapping.json                 # Ranger-to-IAM principal mappings
```

### 4. Start the Sync Service

The recommended entry point is `ConversionServerMain`, which provides structured logging, CloudWatch metrics, and graceful shutdown:

```bash
java -cp ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  com.amazonaws.policyconverters.app.ConversionServerMain \
  /path/to/server-config.yaml
```

A legacy `SyncServiceMain` entry point is also available for simpler deployments without the server wrapper (no CloudWatch metrics or structured JSON logging):

```bash
java -cp ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  com.amazonaws.policyconverters.app.SyncServiceMain \
  /path/to/sync-config.yaml
```

Both entry points block on the main thread until a shutdown signal (SIGTERM/SIGINT) is received, at which point they gracefully finish the current sync cycle and exit.

On first startup with an empty checkpoint, the first policy refresh performs a bulk sync (all current policies are treated as new grants).

## Deployment (Docker Compose via Maven)

Two Maven profiles automate Docker Compose deployment. Maven generates `server-config-deploy.yaml` and `.env` from `-D` command-line properties — no manual file editing required.

### Deploy the Conversion Server

Requires an existing Ranger Admin instance (or use the local stack below). Run from the project root:

```bash
# Start
mvn generate-resources exec:exec@ensure-network exec:exec@docker-compose-up \
  -Pdeploy-server \
  -Daws.account.id=123456789012 \
  -Daws.role.arn=arn:aws:iam::123456789012:role/LakeFormationRole \
  -Daws.access.key.id=AKIAIOSFODNN7EXAMPLE \
  -Daws.secret.access.key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY

# Stop
mvn exec:exec@docker-compose-down -Pdeploy-server
```

Optional properties: `-Daws.region=us-east-1`, `-Dranger.admin.url=http://ranger-admin:6080`, `-Dranger.admin.password=rangerR0cks!`, `-Dprincipal.user.mappings=alice=arn:aws:iam::123:user/Alice,bob=arn:aws:iam::123:user/Bob`. See [`deploy/README.md`](deploy/README.md) for the full property reference.

### Deploy a Local Ranger Admin Stack

If you don't have a running Ranger Admin, start one locally:

```bash
# Start (ranger-db, ranger-solr, ranger-admin)
mvn exec:exec@ensure-network exec:exec@docker-compose-up-ranger -Pdeploy-ranger-admin

# Stop
mvn exec:exec@docker-compose-down -Pdeploy-ranger-admin
```

When both profiles are running, the conversion-server reaches `ranger-admin` via Docker DNS on the shared `rangernw` network (the default `ranger.admin.url`).

For full details on Maven properties, principal mapping format, troubleshooting, and combined usage, see [`deploy/README.md`](deploy/README.md).

## Configuration

### YAML Configuration File

```yaml
rangerConfig:
  rangerAdminUrl: "http://ranger-admin:6080"
  username: "admin"
  password: "admin_password"
  maxRetries: 3
  retryBackoffMs: 1000
  # Kerberos authentication (alternative to username/password)
  # kerberosKeytab: "/etc/security/keytabs/ranger.keytab"
  # kerberosPrincipal: "ranger/host@EXAMPLE.COM"

awsConfig:
  region: "us-east-1"
  catalogId: "123456789012"
  roleArn: "arn:aws:iam::123456789012:role/RangerLFSyncRole"
  # Static credentials (optional, not recommended for production)
  # accessKey: "AKIAIOSFODNN7EXAMPLE"
  # secretKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

principalMapping:
  userMappings:
    analyst: "arn:aws:iam::123456789012:role/AnalystRole"
    etl_user: "arn:aws:iam::123456789012:role/ETLRole"
  groupMappings:
    data_engineers: "arn:aws:iam::123456789012:role/DataEngineerRole"
  roleMappings:
    admin_role: "arn:aws:iam::123456789012:role/AdminRole"

policyRefreshIntervalMs: 30000
wildcardRefreshIntervalSeconds: 300  # 0 or absent = disabled
maxLfRetries: 5
lfRetryBackoffMs: 2000
deadLetterLogPath: "/var/log/ranger/lakeformation/dead-letter.jsonl"
checkpointPath: "/var/lib/ranger/lakeformation/sync-checkpoint.json"

server:
  shutdownTimeoutSeconds: 30
  logLevel: INFO                # TRACE | DEBUG | INFO | WARN | ERROR
  metricsNamespace: RangerLFSync

reverseSync:
  enabled: false
  reportOnly: false
  dryRun: false
  periodicIntervalMs: 0
  # catalogId: "123456789012"   # defaults to awsConfig.catalogId
  exclusionFilter:
    excludedPrincipals: []
    excludedResourcePatterns: []
```

The Ranger connection supports both username/password and Kerberos authentication. At least one must be configured.


### Multi-Service Ranger Configuration

To sync policies from multiple Ranger service types simultaneously, add a `rangerServices` list:

```yaml
rangerServices:
  - serviceType: lakeformation
    serviceInstanceName: lf_prod

  - serviceType: hive
    serviceInstanceName: hive_prod

  - serviceType: presto
    serviceInstanceName: presto_prod
    gdcCatalogName: awsdatacatalog  # required for presto/trino

  - serviceType: trino
    serviceInstanceName: trino_prod
    gdcCatalogName: glue_catalog    # required for presto/trino
```

When `rangerServices` is omitted or empty, the server defaults to a single LakeFormation service for backward compatibility.

Presto and Trino services require a `gdcCatalogName` property identifying which catalog maps to the Glue Data Catalog. Policies targeting other catalogs are silently skipped.

### Environment Variable Overrides

All configuration values can be overridden via environment variables. Environment variables take precedence over file-based values.

| Environment Variable | Config Field |
|---------------------|-------------|
| `RANGER_ADMIN_URL` | `rangerConfig.rangerAdminUrl` |
| `RANGER_USERNAME` | `rangerConfig.username` |
| `RANGER_PASSWORD` | `rangerConfig.password` |
| `AWS_REGION` | `awsConfig.region` |
| `AWS_CATALOG_ID` | `awsConfig.catalogId` |
| `AWS_ACCESS_KEY` | `awsConfig.accessKey` |
| `AWS_SECRET_KEY` | `awsConfig.secretKey` |
| `AWS_ROLE_ARN` | `awsConfig.roleArn` |
| `POLICY_REFRESH_INTERVAL_MS` | `policyRefreshIntervalMs` |
| `MAX_LF_RETRIES` | `maxLfRetries` |
| `LF_RETRY_BACKOFF_MS` | `lfRetryBackoffMs` |
| `DEAD_LETTER_LOG_PATH` | `deadLetterLogPath` |
| `SERVER_SHUTDOWN_TIMEOUT_SECONDS` | `server.shutdownTimeoutSeconds` |
| `SERVER_LOG_LEVEL` | `server.logLevel` |
| `SERVER_METRICS_NAMESPACE` | `server.metricsNamespace` |

### Principal Mapping

The principal mapping maps Ranger identities to AWS IAM ARNs:

```json
{
  "userMappings": {
    "analyst": "arn:aws:iam::123456789012:role/AnalystRole",
    "etl_user": "arn:aws:iam::123456789012:role/ETLRole"
  },
  "groupMappings": {
    "data_engineers": "arn:aws:iam::123456789012:role/DataEngineerRole"
  },
  "roleMappings": {
    "admin_role": "arn:aws:iam::123456789012:role/AdminRole"
  }
}
```

Principals with no configured mapping are skipped with a warning log.

## Multi-Service Ranger Support

The plugin supports simultaneous policy sync from multiple Ranger service types. Each service type has its own adapter that maps access types and resource hierarchies to the shared Cedar/Lake Formation model.

### Supported Service Types

| Service Type | Resource Hierarchy | GDC Catalog Filtering |
|---|---|---|
| `lakeformation` | database → table → column, data location | No |
| `hive` | database → table → column | No |
| `presto` | catalog → schema → table → column | Yes (requires `gdcCatalogName`) |
| `trino` | catalog → schema → table → column | Yes (requires `gdcCatalogName`) |

For Presto and Trino, the adapter maps "schema" to "database" in the DataCatalog model and filters policies by the configured `gdcCatalogName`.

### Hive Access Type Mapping

| Hive Access Type | Cedar Action | Notes |
|---|---|---|
| `select` | SELECT | |
| `update` | INSERT | |
| `create` | CREATE_TABLE | |
| `drop` | DROP | |
| `alter` | ALTER | |
| `read` | SELECT | Alias |
| `write` | INSERT | Alias |
| `all` | SUPER | Expanded |
| `index` | _(unmapped)_ | Logged and skipped |
| `lock` | _(unmapped)_ | Logged and skipped |

### Presto / Trino Access Type Mapping

| Access Type | Cedar Action | Notes |
|---|---|---|
| `select` | SELECT | |
| `insert` | INSERT | |
| `delete` | DELETE | |
| `create` | CREATE_TABLE | |
| `drop` | DROP | |
| `alter` | ALTER | |
| `use` | DESCRIBE | |
| `show` | DESCRIBE | |
| `grant` | _(unmapped)_ | Logged and skipped |
| `revoke` | _(unmapped)_ | Logged and skipped |

### Cedar Policy Namespace Isolation

Cedar policies from different service types are prefixed with the service type in the `@source` annotation (e.g., `@source("hive:42")`). This ensures that adding a new service type does not trigger revocations of policies from other services.

### Per-Service Checkpoint Tracking

The checkpoint store tracks a per-service policy version map alongside the merged Cedar policy text. Legacy single-version checkpoints are backward-compatible (treated as a single `lakeformation` entry).

## LakeFormation Access Type Mapping

| Ranger Access Type | Lake Formation Permission | Notes |
|-------------------|--------------------------|-------|
| `select` | `SELECT` | Direct mapping |
| `insert` | `INSERT` | Direct mapping |
| `delete` | `DELETE` | Direct mapping |
| `describe` | `DESCRIBE` | Direct mapping |
| `alter` | `ALTER` | Direct mapping |
| `drop` | `DROP` | Direct mapping |
| `create_database` | `CREATE_DATABASE` | Catalog-level creation |
| `create_table` | `CREATE_TABLE` | Database-level creation |
| `update` | `INSERT` | Legacy alias |
| `create` | `CREATE_TABLE` | Legacy alias |
| `read` | `SELECT` | Legacy alias |
| `write` | `INSERT` | Legacy alias |
| `all` | `SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE` | Expanded to all applicable permissions |
| `datalocation` | `DATA_LOCATION_ACCESS` | S3 data location access |
| `data_location_access` | `DATA_LOCATION_ACCESS` | S3 data location access |

## Wildcard Pattern Refresh

When Ranger policies use glob patterns (e.g., `table_*`, `db_?`) to match resources by name, the system expands them against the Glue Data Catalog at conversion time. The bare `*` wildcard (ALL tables) is handled natively by Lake Formation's `TableWildcard`.

For glob patterns that match a subset of resources, a periodic refresh scheduler re-evaluates them against the current catalog state. When new resources appear (or old ones disappear) that match existing patterns, the system computes a delta and applies the necessary grant/revoke operations.

Configure via `wildcardRefreshIntervalSeconds` in the YAML config (set to `0` or omit to disable). The refresh cycle acquires a shared lock with the normal sync cycle to prevent concurrent updates.

## Reverse Sync / Drift Detection

The reverse-sync feature retrieves actual Lake Formation permissions via the `ListPermissions` API, normalizes them into the same `LFPermissionOperation` model, computes a diff against the Cedar-authoritative desired state, and applies corrective operations.

This addresses drift caused by out-of-band changes (console edits, other automation, partial apply failures).

Configure via the `reverseSync` section in the YAML config:

| Field | Description | Default |
|-------|-------------|---------|
| `enabled` | Enable the reverse-sync feature | `false` |
| `reportOnly` | Compute drift report without applying corrections | `false` |
| `dryRun` | Serialize corrections to JSON instead of applying | `false` |
| `periodicIntervalMs` | Periodic interval (0 = after each forward-sync only) | `0` |
| `exclusionFilter.excludedPrincipals` | Principal ARNs to exclude from drift detection | `[]` |
| `exclusionFilter.excludedResourcePatterns` | Resource patterns to exclude | `[]` |

Corrective operations are ordered REVOKEs-first to avoid transient over-permissioning. Individual failures are logged to the dead-letter log without aborting the remaining batch. An empty Cedar policy set is a safety guard that skips the cycle to prevent mass revocation.

## Limitations and Unsupported Features

The following Ranger features have no direct equivalent in Lake Formation. When encountered, the plugin converts the supported portions of the policy and records the unsupported portions in the gap report.

| Ranger Feature | Status | Details |
|---------------|--------|---------|
| Data masking policies | Not supported | LF does not support native data masking. Consider column-level permissions or external masking solutions. |
| Tag-based policies | Not supported | Ranger tags are structurally different from LF-Tags. Requires manual mapping. |
| Deny policies | Not supported | LF uses a grant-only model. Deny rules cannot be represented. |
| Deny exceptions | Not supported | LF uses a grant-only model. |
| Validity schedules (time-bound policies) | Not supported | LF does not support temporal policy constraints. |
| Custom conditions (IP-based, geo-based) | Not supported | LF does not support conditional policies. |
| Security zones | Not supported | LF has no equivalent concept. |
| Delegated admin | Partial | Recorded in gap report. LF uses a different admin delegation model (`WITH GRANT OPTION`). |
| Policy deltas from Ranger Admin | Not yet handled | The plugin currently processes only full policy snapshots. Delta updates from Ranger Admin (Ranger 2.0+) are not yet supported and may cause incorrect behavior. This is a known issue planned for a future release. |

## Gap Report

The gap report is a JSON file listing all unsupported features encountered during conversion:

```json
{
  "entries": [
    {
      "policyId": "42",
      "policyName": "mask-ssn",
      "gapType": "DATA_MASKING",
      "resourcePath": "hr_db/employees",
      "details": "Data masking policy items detected. Lake Formation does not support native data masking.",
      "recommendation": "Consider using column-level permissions or external masking solutions."
    }
  ],
  "summary": {
    "DATA_MASKING": 1
  },
  "generatedAt": "2025-01-15T10:30:00Z"
}
```

## Dead-Letter Log

Failed operations are written in JSON-lines format:

```json
{"timestamp":"2025-01-15T10:30:00Z","policyId":"42","operation":"GRANT","resource":{"database":"analytics","table":"events"},"principal":"arn:aws:iam::123456789012:role/DataAnalyst","permissions":["SELECT"],"error":"ConcurrentModificationException","retryCount":5}
```

## Cedar Policy Schema

The conversion pipeline uses an intermediate Cedar policy representation validated against a DataCatalog schema (`src/main/resources/cedar/datacatalog.cedarschema`). The schema defines valid entity types and action-resource constraints:

| Cedar Action | Valid Resource Types |
|---|---|
| `SELECT` | Table, Column |
| `INSERT` | Table |
| `UPDATE` | Table |
| `DELETE` | Table |
| `DESCRIBE` | Catalog, Database, Table |
| `ALTER` | Database, Table |
| `DROP` | Database, Table |
| `CREATE_DATABASE` | Catalog |
| `CREATE_TABLE` | Database |
| `DATA_LOCATION_ACCESS` | DataLocation |

Cedar policy statements that fail schema validation are excluded and recorded in the gap report.

## Architecture

The codebase is organized by responsibility:

| Package | Purpose |
|---------|---------|
| `app` | Entry points (`ConversionServerMain`, `SyncServiceMain`, `ServiceDefInstallerMain`), process lifecycle (`ServerLifecycle`), sync cycle execution (`SyncCycleExecutor`), and wildcard refresh scheduling (`WildcardRefreshScheduler`) |
| `config` | All configuration classes, loaders, and validators (`SyncConfig`, `ServerConfig`, `RangerServiceConfig`, `ReverseSyncConfig`) |
| `cedar` | Cedar policy language bridge layer (`CedarPolicySet`, `CedarToLFConverter`, `SourcePolicyAdapter`) |
| `ranger` | Ranger-specific logic: plugin, policy-to-Cedar conversion, catalog resolution, service definition, glob pattern detection |
| `ranger/service` | Multi-service Ranger support: `BaseRangerService` abstract class and concrete implementations (`LakeFormationRangerService`, `HiveRangerService`, `PrestoRangerService`, `TrinoRangerService`), service definition installer |
| `lakeformation` | Lake Formation API client, IAM principal mapping, LF-specific models, permission fetcher for reverse sync, drift detection |
| `sync` | Forward and reverse sync orchestration, checkpointing, dead-letter logging, drift detection (`DriftDetector`, `ReverseSyncService`) |
| `model` | Shared domain models (gap reports, drift reports, dry-run output, sync cycle results, wildcard refresh results) |
| `reporting` | Gap reporting, CloudWatch metrics (`MetricsEmitter`), structured error logging |
| `deploy` | Deploy template utilities |

```
┌─────────────────────┐         ┌──────────────────────────────────────────┐
│   Ranger Admin      │         │  Conversion Server                       │
│                     │ policy  │                                          │
│  Service Definitions├────────►│  BaseRangerService (per service type)    │
│  (lakeformation,    │ refresh │    ├─► LakeFormationRangerService        │
│   hive, presto,     │         │    ├─► HiveRangerService                 │
│   trino)            │         │    ├─► PrestoRangerService               │
│                     │         │    └─► TrinoRangerService                │
│  Policies           │         │         │                                │
│                     │         │         ▼                                │
└─────────────────────┘         │  RangerToCedarConverter (adapter registry│
                                │    + CatalogResolver + PrincipalMapper)  │
                                │         │                                │
                                │         ▼                                │
                                │  CedarPolicySet (merged, all services)   │
                                │         │                                │
                                │         ▼                                │
                                │  CedarToLFConverter                      │
                                │         │                                │
                                │    ┌────┴────┐                           │
                                │    ▼         ▼                           │
                                │  SyncService  ReverseSyncService         │
                                │  (forward)    (drift correction)         │
                                │    │              │                      │
                                │    ▼              ▼                      │
                                │  LakeFormationClient                     │
                                │    ├─► GapReporter                       │
                                │    ├─► DeadLetterLogger                  │
                                │    ├─► CheckpointStore                   │
                                │    └─► MetricsEmitter → CloudWatch       │
                                └──────────┬───────────────────────────────┘
                                           │ grant/revoke/listPermissions
                                           ▼
                                ┌──────────────────────┐
                                │  AWS Lake Formation   │
                                │  Glue Data Catalog    │
                                └──────────────────────┘
```

## Docker Container

The project includes a multi-stage `Dockerfile` that builds the fat JAR and packages it into an `eclipse-temurin:17-jre` runtime image. The container:

- Uses `SIGTERM` as the stop signal for graceful shutdown
- Includes a `HEALTHCHECK` via `pgrep`
- Accepts a config file path as the entrypoint argument

```bash
docker build -t ranger-lf-sync .
docker run -v /path/to/config.yaml:/app/config.yaml ranger-lf-sync
```

## CloudWatch Metrics

The conversion server publishes operational metrics to a configurable CloudWatch namespace (default: `RangerLFSync`). All metrics include a `ServiceName=conversion-server` dimension.

| Metric | Unit | When Published |
|--------|------|----------------|
| `SyncCycleSuccess` | Count (1) | Sync cycle succeeds |
| `SyncCycleFailure` | Count (1) | Sync cycle fails |
| `SyncCycleDuration` | Milliseconds | Sync cycle succeeds |
| `PoliciesProcessed` | Count | Every cycle |
| `GrantsApplied` | Count | Every cycle |
| `RevocationsApplied` | Count | Every cycle |
| `ErrorCount` | Count (1) | On error (includes `ErrorType` dimension) |
| `PluginFetchFailure` | Count (1) | Ranger plugin fetch fails (includes `ServiceType` dimension) |
| `WildcardRefreshSuccess` / `WildcardRefreshFailure` | Count (1) | Wildcard refresh cycle completes |
| `WildcardRefreshDuration` | Milliseconds | Wildcard refresh cycle completes |
| `WildcardRefreshDeltaOperations` | Count | Wildcard refresh cycle completes |
| `UnmappedAccessType` | Count (1) | Unknown access type encountered |

## License

Apache License 2.0
