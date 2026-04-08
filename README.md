
> This is a work in progress and has a lot of work left. Please contact hocanint@amazon.com if you wish to contribute.

# Ranger Lake Formation Sync Plugin

A Java utility that bridges Apache Ranger access control policies to AWS Lake Formation permissions. It converts Ranger's policy model (allow/deny rules on users, groups, roles) into Lake Formation's grant/revoke permission model, enabling organizations to manage Lake Formation permissions through Ranger Admin.

## Features

- **Bulk Export & Convert**: One-shot extraction of all Ranger policies via REST API, conversion to Lake Formation permissions, and batch application.
- **Real-Time Sync**: A `RangerBasePlugin` that receives policy updates from Ranger Admin and incrementally applies changes to Lake Formation.
- **Service Definition Installation**: A custom "lakeformation" service type registered in Ranger Admin, enabling policy authoring for Lake Formation resources through the Ranger UI.
- **Incremental Diff**: Computes deltas between policy snapshots rather than revoking all and re-granting, minimizing Lake Formation API calls.
- **Gap Reporting**: Produces a structured JSON report of Ranger features that have no Lake Formation equivalent (data masking, deny policies, etc.).
- **Wildcard Expansion**: Expands wildcard resource patterns (e.g., `db_*`) against the Glue Data Catalog since Lake Formation does not support wildcard grants.
- **Atomic Per-Policy Application**: Permission changes for a single Ranger policy are applied as a unit with rollback on failure.
- **Dead-Letter Log**: Failed operations that exhaust retries are written to a JSON-lines file for manual remediation.
- **Principal Mapping**: Configurable mapping from Ranger users/groups/roles to AWS IAM principal ARNs.

## Requirements

- Java 17 (JDK 17+)
- Apache Maven 3.6+
- Apache Ranger 2.4+ (compatible with 2.4, 2.6, 2.7, 2.8)
- AWS account with Lake Formation and Glue Data Catalog access
- IAM credentials with permissions for `lakeformation:GrantPermissions`, `lakeformation:RevokePermissions`, `glue:GetDatabases`, `glue:GetTables`, `glue:GetTable`

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

Integration tests run the full Ranger → Cedar → Lake Formation pipeline against a live Docker Ranger Admin instance using a dry-run client that writes LF operations to JSON files instead of calling AWS APIs.

```bash
# Full lifecycle: start Ranger, run tests, tear down
./integration-test/scripts/run-integration-tests.sh

# Or manually:
./integration-test/scripts/start-ranger.sh
mvn verify -Pintegration-test
./integration-test/scripts/stop-ranger.sh
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

### 1. Install the Service Definition in Ranger Admin

The service definition tells Ranger Admin about the "lakeformation" service type, its resources (database, table, column), and access types (SELECT, INSERT, DELETE, etc.).

**Option A: REST API installation**

```bash
java -cp ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  org.apache.ranger.lakeformation.ServiceDefInstallerMain \
  --mode rest \
  --config /path/to/sync-config.yaml
```

**Option B: File-based installation**

```bash
java -cp ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  org.apache.ranger.lakeformation.ServiceDefInstallerMain \
  --mode file \
  --ranger-admin-home /opt/ranger-admin
```

This copies the service definition JSON to `ranger-admin/ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation/`. Restart Ranger Admin after file-based installation.

Both modes accept an optional `--service-def <path>` flag to use a custom service definition JSON instead of the bundled one.

### 2. Create a Lake Formation Service in Ranger Admin

After installing the service definition, create a new service of type "lakeformation" in the Ranger Admin UI. Configure:

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

```bash
java -cp ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  org.apache.ranger.lakeformation.SyncServiceMain \
  /path/to/sync-config.yaml
```

On first startup with an empty previous snapshot, the first policy refresh performs a bulk sync (all current policies are treated as new grants).

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

awsConfig:
  region: "us-east-1"
  catalogId: "123456789012"
  roleArn: "arn:aws:iam::123456789012:role/RangerLFSyncRole"

principalMapping:
  userMappings:
    analyst: "arn:aws:iam::123456789012:role/AnalystRole"
    etl_user: "arn:aws:iam::123456789012:role/ETLRole"
  groupMappings:
    data_engineers: "arn:aws:iam::123456789012:role/DataEngineerRole"
  roleMappings:
    admin_role: "arn:aws:iam::123456789012:role/AdminRole"

policyRefreshIntervalMs: 30000
maxLfRetries: 5
lfRetryBackoffMs: 2000
deadLetterLogPath: "/var/log/ranger/lakeformation/dead-letter.jsonl"
```

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

### Principal Mapping

The principal mapping file maps Ranger identities to AWS IAM ARNs:

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

## Access Type Mapping

| Ranger Access Type | Lake Formation Permission | Notes |
|-------------------|--------------------------|-------|
| `select` | `SELECT` | Direct mapping |
| `update` | `INSERT` | Ranger "update" maps to LF "INSERT" |
| `create` | `CREATE_TABLE` | Table-level creation |
| `drop` | `DROP` | Direct mapping |
| `alter` | `ALTER` | Direct mapping |
| `read` | `SELECT` | Alias for select |
| `write` | `INSERT` | Alias for update |
| `all` | `SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE` | Expanded to all applicable permissions |

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
| Reconciliation after drift | Not supported | If Lake Formation permissions drift out of sync (e.g., due to out-of-band changes, partial failures, or plugin restart), there is no automatic reconciliation mechanism. Manual intervention is required. |
| Persistence of sync state | Not supported | The plugin's previous-snapshot state is held in memory. A restart causes a full re-sync from the current Ranger snapshot. |

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

## Architecture

```
┌─────────────────────┐         ┌──────────────────────────────────┐
│   Ranger Admin      │         │  Ranger-LF Sync Plugin           │
│                     │ policy  │                                  │
│  Service Definition ├────────►│  LakeFormationPlugin             │
│  (lakeformation)    │ refresh │    └─► SyncService               │
│                     │         │          ├─► PolicyConverter      │
│  Policies           │         │          ├─► PrincipalMapper      │
│                     │         │          ├─► CatalogResolver      │
└─────────────────────┘         │          ├─► LakeFormationClient  │
                                │          ├─► GapReporter          │
                                │          └─► DeadLetterLogger     │
                                └──────────┬───────────────────────┘
                                           │ grant/revoke
                                           ▼
                                ┌──────────────────────┐
                                │  AWS Lake Formation   │
                                │  Glue Data Catalog    │
                                └──────────────────────┘
```

## License

Apache License 2.0
