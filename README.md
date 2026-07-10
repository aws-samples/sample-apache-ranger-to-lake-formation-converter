
# Ranger Lake Formation Sync Plugin

A Java utility that bridges Apache Ranger access control policies to AWS Lake Formation permissions. It converts Ranger's policy model (allow/deny rules on users, groups, roles) into Lake Formation's grant/revoke permission model via an intermediate Cedar policy representation, enabling organizations to manage Lake Formation permissions through Ranger Admin until customers can completely migrate to Lake Formation.

## Features

- **Multi-Service Ranger Support**: Simultaneously manage policies from multiple Ranger service types — LakeFormation, Apache Hive, EMR Spark, EMRFS, Presto, and Trino — with a unified Cedar policy repository driving Lake Formation permissions.
- **Bulk Export & Convert**: One-shot extraction of all Ranger policies via REST API, conversion to Lake Formation permissions, and batch application.
- **Real-Time Sync**: A `RangerBasePlugin` that receives policy updates from Ranger Admin and incrementally applies changes to Lake Formation.
- **Service Definition Installation**: Custom service types registered in Ranger Admin (lakeformation, hive, amazon-emr-spark, amazon-emr-emrfs, presto, trino), enabling policy authoring for Lake Formation resources through the Ranger UI.
- **Incremental Diff**: Computes deltas between policy snapshots rather than revoking all and re-granting, minimizing Lake Formation API calls.
- **Wildcard Expansion with Periodic Refresh**: Expands wildcard resource patterns (e.g., `db_*`) against the Glue Data Catalog. A configurable periodic refresh re-evaluates glob patterns to pick up newly created resources without waiting for a Ranger policy push.
- **Reverse Sync / Drift Detection**: Retrieves actual Lake Formation permissions via `ListPermissions`, computes drift against the Cedar-authoritative state, and applies corrective GRANT/REVOKE operations to reconcile out-of-band changes.
- **Cedar Policy Bridge**: An intermediate Cedar policy representation provides a formal, schema-validated layer between Ranger policies and Lake Formation permissions.
- **Atomic Per-Policy Application**: Permission changes for a single Ranger policy are applied as a unit with rollback on failure.
- **Checkpoint Persistence**: Sync state (Cedar policy text and per-service Ranger policy versions) is persisted to a JSON checkpoint file, surviving restarts without a full re-sync.
- **Gap Reporting**: Produces a structured JSON report of Ranger features that have no Lake Formation equivalent (data masking, deny policies, etc.).
- **Pre-Migration Assessment**: A one-time CLI tool that connects to Ranger Admin, runs the full conversion pipeline in read-only mode, and reports how many policies will convert cleanly, partially, or not at all — with projected Lake Formation grant counts and a per-gap-type breakdown. No AWS credentials or Lake Formation access required.
- **Dead-Letter Log**: Failed operations that exhaust retries are written to a JSON-lines file for manual remediation.
- **Principal Mapping**: Configurable mapping from Ranger users/groups/roles to AWS principal ARNs. Supports a static file-based mapper and an AWS IAM Identity Center mapper that resolves directory identities to IDC principals at runtime via the IdentityStore API.
- **Structured Logging**: JSON-formatted logs to stdout/stderr for container log collection (e.g., Fluent Bit to CloudWatch Logs).
- **CloudWatch Metrics**: Publishes operational metrics (sync cycle success/failure, duration, grants/revocations applied, error counts) to a configurable CloudWatch namespace.
- **Dry-Run Mode**: Serializes LF operations to JSON files instead of calling AWS APIs, for testing and human review.

> For limitations, see [Limitations](#considerations-and-limitations).

# Recommended Usage of this tool

Migration from Apache Ranger to Lake Formation can be a daunting task, which is why this tool was created. We attempt to make it easier and simplier to transition to Lake Formation. Below are a recommended set of steps to run during a migration.

1. **Use Pre-migration Assessment Tool** We have created a [Pre-Migration Assessment Tool](#pre-migration-assessment-tool) so that you can validate your existing Ranger policies and to detect if there are any policies that cannot be migrated. It is **highly** recommended that you run this tool to get an understanding of how this tool works, which policies it will be able to support, and any gaps that may exist. 

2. **Obtain a testing AWS account** that will be used as the target of this tool. This account will be used to validate LF policies, make it run for some time to validate that everything is working properly, and address any issues before going to production. 

3. **Optional: Configure Lake Formation** You may want to configure the account to only use Lake Formation or not. Either way works. If you do not want to enable Lake Formation enforcement, and just see the policies within Lake Formation, you can do that and once there is high confidence in the usage of the tool, you can slowly enable databases/tables and users to use Lake Formation enforcement.

4. **Configure the migration tool** - See [Configuration](#configuration) section 

4. **User Identities** - It will need to be decided how to map Ranger Identities to AWS Identities. Ranger typically will use directory identities via LDAP. However, AWS does not natively support LDAP to query these identities directly. There are two approaches that can be taken:

    a) Map directory identities to IAM roles. 

    b) or configure AWS Identity Center to sync with a directory to pull in the identities into AWS. You will also need to enable Trusted Identity Propogation feature for all AWS analytic services that will be used for Lake Formation to performance authorization on identities. 
See section [Principal Mapping](#principal-mapping) on how to configure either approach. 

5. **Run the Sync tool for the first time** using the [Reverse Sync](#reverse-sync--drift-detection) enabled and allow it to run for atleast one week. The tool will report to CloudWatch metrics the number of policies it has converted, partially convert, or is unable to convert. It will also report errors, and other useful metrics. 

6. **Re-run the sync tool right prior to using in production** Once there is confidence, right before running on a live production environment, remove all Lake Formation permissions in the test account, and re-run the sync job to ensure that nothing has changed. Once that runs successfully, run it in production.

7. **Manage Permissions in Apache Ranger** Continue to manage Lake Formation entirely from Apache Ranger. Lake Formation should not be used to modify permissions until Ranger is completely shut down.

# Considerations and Limitations

The following Ranger features have no direct equivalent in Lake Formation. When encountered, the plugin converts the supported portions of the policy and records the unsupported portions in the gap report.

| Ranger Feature | Status | Details |
|---------------|--------|---------|
| Data masking policies | Not supported | LF does not support native data masking. Consider column-level permissions or external masking solutions. |
| Tag-based policies | Detected, not converted | Detected via service name heuristic (`name.contains("tag")`). The entire policy is skipped; a `TAG_BASED_POLICY` gap entry is recorded. Converting to LF-Tag permissions is planned but not yet implemented. |
| Deny policies | Partial — see [Deny Policy Behaviour](#deny-policy-behaviour) | LF has no deny model. Deny rules are converted to Cedar `forbid` statements and suppress the corresponding grants before they reach LF. This preserves the user's intent but the restriction is not enforced natively in LF. A `DENY_POLICY` gap entry is recorded. |
| Deny exceptions | Partial | Deny exceptions are converted to Cedar `permit @denyException` statements that restore permissions suppressed by a deny. Not natively representable in LF — the net effect (grant restored or not) is reflected in LF permissions but the exception itself is invisible to LF. |
| Validity schedules (time-bound policies) | Not supported | LF does not support temporal policy constraints. |
| Custom conditions (IP-based, geo-based) | Not supported | LF does not support conditional policies. |
| Security zones | Not supported | LF has no equivalent concept. |
| Delegated admin | Partial | Recorded in gap report. LF uses a different admin delegation model (`WITH GRANT OPTION`). |
| Policy deltas from Ranger Admin | Not yet handled | The plugin currently processes only full policy snapshots. Delta updates from Ranger Admin (Ranger 2.0+) are not yet supported and may cause incorrect behavior. This is a known issue planned for a future release. |
| LF tag-policy resources in reverse sync | Silently skipped | `LFPermissionFetcher` drops `CatalogResource` and `LFTagPolicyResource` entries returned by `ListPermissions` with only a log warning — no gap entry is recorded. Out-of-band LF-Tag grants will not be detected or corrected by reverse sync. |
| `WILDCARD_PATTERN` gap not emitted at runtime | Known gap | The `WILDCARD_PATTERN` gap type is defined and documented but is never triggered at runtime. Wildcard expansion failures (when no AWS credentials are available) produce only a log warning; no gap entry is recorded. |
| IDC mapper does not support roles | Limitation | When using the Identity Center principal mapper, Ranger role principals always produce an empty mapping and are silently skipped with an `UnmappedPrincipal` metric. Use the static mapper if you need role-to-IAM-ARN mappings. |

## Features not yet implemented

- **Tag-Based Policy Conversion**: Ranger tag-based policies (services whose name contains `"tag"`) are currently **detected and skipped** — the gap is recorded in the gap report as `TAG_BASED_POLICY` — but no conversion to Lake Formation LF-Tag permissions is performed. Converting Ranger tag policies to LF-Tag-based access control is planned but not yet implemented. Note that LF-Tags and Ranger tags are structurally different, and a full implementation typically requires integration with a tagging service such as Apache Atlas.
- **Row Filters**: We will implement this in the future if there is demand for this.

## Features not planned to be implemented

- **Data Masking**: Data masking is not yet supported within Lake Formation. Although this can be implemented using Glue Views, it will add very significant complexity.
- **ABAC and Conditions**: There is no way to perform this at the moment.
- **Expire_on**: We do not support this today and we would need to implement this capability manually.

## Deny Policy Behaviour

Lake Formation uses a grant-only model — it has no native deny mechanism. Ranger deny policies cannot be expressed directly in LF. However, the sync service uses Cedar as an intermediate representation and Cedar does support `forbid` statements, so deny policies are honoured indirectly:

1. **Conversion**: A Ranger deny policy item is converted to a Cedar `forbid` statement.
2. **Suppression**: During Cedar-to-LF conversion, any `permit` statement that is covered by a `forbid` is removed from the set of LF grants. The corresponding grant is never issued to LF.
3. **Gap recorded**: A `DENY_POLICY` gap entry is written to the gap report for every policy with deny items, because the restriction is not enforced natively inside LF.

**What this means in practice**: if a principal has a Ranger allow policy granting SELECT on a table AND a Ranger deny policy for the same principal/table, the net result in LF will be **no SELECT grant** — matching the user's intent even though LF itself has no deny concept.

### Resource hierarchy in deny suppression

Cedar's entity hierarchy (`Column in [Table]`, `Table in [Database]`, `Database in [Catalog]`) is applied when matching forbids against permits:

| Deny scope | Suppresses grants on |
|---|---|
| Specific table (e.g. `db.orders`) | That table and all its columns |
| Database (e.g. `mydb`) | All tables and columns in that database |
| Catalog | All databases, tables, and columns |

**Example**: a Ranger deny policy scoped to `database=mydb` will suppress SELECT grants on `mydb.orders`, `mydb.products`, and any column-level grants within those tables — even though those grants come from separate allow policies.

### Important limitations

- **Not enforced in LF**: The deny is implemented by omitting grants, not by adding an LF deny. Any permission granted out-of-band (e.g. directly in the LF console, or by another tool) will not be blocked.
- **Reverse sync does not enforce denies**: The reverse sync / drift detection reconciliation only compares against the Cedar-derived desired state. An out-of-band grant that contradicts a Ranger deny will be revoked during the next reconciliation cycle, but only because the desired state contains no grant — the deny itself is not a first-class constraint in LF.
- **Gap report**: Every policy with deny items appears in the gap report as `DENY_POLICY`. Review these entries to understand which restrictions are being enforced via grant-omission rather than native LF denies.

### Cross-service deny semantics

All configured Ranger services (Hive, Trino, EMR Spark, etc.) are merged into a single Cedar evaluation namespace. A `forbid` from any service suppresses a `permit` for the same principal from any other service for the same action and resource. This means a Trino deny policy will suppress a Hive grant for the same resource and vice versa. Scope deny policies carefully when using multiple Ranger services.

## TABLE and TABLE\_WITH\_COLUMNS Conflict Resolution

Lake Formation enforces a hard constraint: **a principal cannot hold both a `TABLE` grant and a `TABLE_WITH_COLUMNS` grant for the same table at the same time**, regardless of which permissions each grant carries. Attempting to apply the second grant while the first exists results in an API error.

This creates an irreconcilable conflict when two Ranger policies produce grants of different LF resource types for the same `(principal, table)` combination. The sync service detects and resolves this automatically.

### Conflict patterns

**Pattern A — complementary intent, irreconcilable in LF**

A principal needs both column-restricted SELECT and non-SELECT table operations:

```
Policy 100 (id=100):  TABLE   DESCRIBE, DROP     ← TABLE grant (no SELECT)
Policy 200 (id=200):  TWC     SELECT on [col_a, col_b]  ← TABLE_WITH_COLUMNS grant
```

These policies are complementary in intent, but LF cannot hold both simultaneously. The sync service must pick one.

**Pattern B — write perms lost when TWC wins**

```
Policy 100 (id=100):  TWC     SELECT on [created_at]   ← TABLE_WITH_COLUMNS grant (wins)
Policy 200 (id=200):  TABLE   INSERT, DELETE           ← TABLE grant (loses — permanently)
```

INSERT/DELETE cannot be placed on a `TABLE_WITH_COLUMNS` resource, so the losing permissions are structurally unrepresentable in LF.

### Tiebreaker rule

When a conflict is detected, the policy with the **lower numeric Ranger policy ID** wins. Its LF grant is applied; the competing policy's grant is permanently suppressed.

If two conflicting policies share the same ID (not possible in practice, but guarded against), lexicographic comparison of the full policy ID string is used as a fallback.

### Behavior in the sync service

- **Detection**: `SyncService.detectAndGapTableTwcConflicts` inspects all pending GRANT operations for each `(principal, table)` key. Any group containing both a `TABLE` grant and a `TABLE_WITH_COLUMNS` grant is a conflict.
- **Suppression**: The losing policy's operations are removed from the batch before any LF calls are made. LF never sees both resource types simultaneously.
- **Dead-letter logging**: The first time a conflict is detected for a given `(principal, table)`, all losing operations are written to the dead-letter log (`dead-letter.log`) with `type=GAP` and reason `CONFLICTING_LF_RESOURCE_TYPE`. Subsequent sync cycles do not re-log the same conflict.
- **No automatic merging**: The sync service does not attempt to merge permissions from both policies onto the winning resource type. Write permissions (INSERT, DELETE) cannot be expressed on a `TABLE_WITH_COLUMNS` resource.

### Re-instatement when the winning policy is removed

If the **winning** policy is disabled or deleted in Ranger, the formerly-suppressed policy is automatically reinstated on the next sync cycle:

1. The winning policy no longer contributes a GRANT operation.
2. No conflict is detected — only one resource type remains.
3. The formerly-losing policy's grant is applied to LF.
4. The sync service issues the winning policy's REVOKE *before* the new GRANT in the same batch, so LF never sees both types simultaneously.

This means conflicts are self-healing: removing the winning policy is sufficient to restore the other policy's permissions without any manual intervention.

### Gap report entries

Suppressed operations appear in the dead-letter log as:

```json
{
  "type": "GAP",
  "reason": "CONFLICTING_LF_RESOURCE_TYPE: TABLE and TABLE_WITH_COLUMNS grants cannot coexist ...",
  "resource": { "databaseName": "mydb", "tableName": "orders", "columns": ["col_a"] },
  "principal": "arn:aws:iam::123456789012:role/analyst",
  "permission": "SELECT"
}
```

The presence of a `columns` field identifies a Pattern A loss (TWC suppressed). Absence of `columns` identifies a Pattern B loss (TABLE suppressed).

### Recommendations

- **Avoid authoring conflicting policies.** If a principal needs both column-restricted SELECT and write permissions on the same table, consider: (a) granting full TABLE SELECT instead of column-restricted access, (b) splitting the principal into two roles, or (c) accepting that write permissions will be lost.
- **Review the dead-letter log** after initial sync to identify any conflicts in your Ranger policy set. Address them by consolidating or restructuring the affected policies.
- **Lower policy IDs win.** If you must have conflicting policies and need a specific one to take effect, ensure it has a lower numeric ID in Ranger Admin.

# Design

See [DESIGN.MD](./DESIGN.md) for a deep dive on the design of this tool.

# Usage Instructions

## Requirements

- Java 17 (JDK 17+)
- Apache Maven 3.6+
- Apache Ranger 2.4+ (compatible with 2.4, 2.6, 2.7, 2.8)
- AWS account with Lake Formation and Glue Data Catalog access
- IAM credentials with permissions for `lakeformation:GrantPermissions`, `lakeformation:RevokePermissions`, `lakeformation:ListPermissions`, `glue:GetDatabases`, `glue:GetTables`, `glue:GetTable`
- (IDC mapper only) `identitystore:GetUserId`, `identitystore:GetGroupId`

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

**In-process (DryRun) tests** exercise the full conversion pipeline against a live Ranger Admin using a dry-run Lake Formation client that writes permission operations to JSON files instead of calling AWS. No real AWS credentials required.

**Containerized tests** run the conversion server as a Docker container alongside Ranger Admin. The server registers as a real Ranger plugin, receives policy updates via the plugin refresh mechanism, and writes dry-run output to a bind-mounted directory. Tests create/update/delete Ranger policies via REST and validate the resulting JSON files from the host.

Both test types use pre-configured synthetic AWS credentials and account IDs — no AWS account or IAM setup is required to run them.

#### Automated lifecycle (recommended)

Maven manages the full Docker lifecycle — build, start, provision, test, tear down:

```bash
mvn verify -Pintegration-test
```

This:
1. Builds the fat JAR
2. Builds `Dockerfile.it` into a local Docker image (using the pre-built JAR — no inner Maven build)
3. Starts `ranger-db`, `ranger-solr`, `ranger-admin` and waits for readiness
4. Installs the Lake Formation service definition and creates the service instance
5. Starts the `conversion-server` container
6. Runs all `*IT.java` tests via Maven Failsafe
7. Tears down the stack (even if tests fail)
8. Generates an HTML report at `target/site/failsafe-report.html`

```bash
open target/site/failsafe-report.html
```

To run only the DryRun tests (faster — no containerized server needed):

```bash
mvn verify -Pintegration-test -Dit.test="*DryRun*" -Dfailsafe.failIfNoSpecifiedTests=false
```

#### Manual stack for exploratory testing

Start the full stack and keep it running:

```bash
mvn package -Pstart-stack -DskipTests
```

This builds the JAR and Docker image, starts all four services in the correct order, and installs the Ranger service definition and instance. The stack stays up so you can:
- Create Ranger policies in the Admin UI at `http://localhost:6080` (credentials: `admin` / `rangerR0cks!`)
- Watch dry-run JSON files appear in `integration-test/docker/dry-run-output/`

Check stack status:
```bash
docker compose -f integration-test/docker/docker-compose.yml ps
```

Tear down when done:
```bash
mvn validate -Pstop-stack
```

#### Debugging failed test runs

The legacy `run-integration-tests.sh` script remains available for debugging. It provides Docker preflight checks, a `--skip-teardown` flag to leave the stack running after a failure, and a more robust cleanup trap:

```bash
./integration-test/scripts/run-integration-tests.sh --skip-teardown
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
  -Daws.access.key.id=EXAMPLE_ACCESS_KEY_ID \
  -Daws.secret.access.key=EXAMPLE_SECRET_ACCESS_KEY

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
  # accessKey: "EXAMPLE_ACCESS_KEY_ID"
  # secretKey: "EXAMPLE_SECRET_ACCESS_KEY"

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

  - serviceType: amazon-emr-emrfs
    serviceInstanceName: emrfs
    gdcCatalogName: glue_catalog

  - serviceType: amazon-emr-spark
    serviceInstanceName: spark
    gdcCatalogName: glue_catalog

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

  - serviceType: amazon-emr-emrfs
    serviceInstanceName: emrfs
    gdcCatalogName: glue_catalog

  - serviceType: amazon-emr-spark
    serviceInstanceName: spark
    gdcCatalogName: glue_catalog

  - serviceType: amazon-emr-trino
    serviceInstanceName: trino
    gdcCatalogName: glue_catalog
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

Two mapper types are supported, selected via the `type` field. When `type` is absent the static mapper is used for backward compatibility.

**Static mapper (default)** — mappings maintained in the config file:

```yaml
principalMapping:
  userMappings:
    analyst: "arn:aws:iam::123456789012:role/AnalystRole"
    etl_user: "arn:aws:iam::123456789012:role/ETLRole"
  groupMappings:
    data_engineers: "arn:aws:iam::123456789012:role/DataEngineerRole"
  roleMappings:
    admin_role: "arn:aws:iam::123456789012:role/AdminRole"
```

**Identity Center mapper** — resolves Ranger users and groups to IDC principals at runtime via the AWS IdentityStore API. Produces ARNs of the form `arn:aws:identitystore::<accountId>:user/<UUID>` / `:group/<UUID>`, suitable for Lake Formation grants targeting IDC-synced identities. Lookups are cached with a configurable TTL (default 60 minutes):

```yaml
principalMapping:
  type: IDENTITY_CENTER
  idcConfig:
    identityStoreId: "d-1234567890"   # required — Identity Store ID
    region: "us-east-1"               # required — IdentityStore API region
    accountId: "123456789012"         # required — used in principal ARN construction
    cacheTtlMinutes: 60               # optional, default 60
```

Roles are not represented in Identity Center; role principals always produce an empty mapping. Principals with no configured or discoverable mapping are skipped with a warning log and an `UnmappedPrincipal` CloudWatch metric.

**Composite mapper** — chains multiple delegates in order; the first delegate that resolves a principal wins. Use this when you need both IAM role mappings (for service accounts and Ranger roles) and Identity Center resolution (for human users and groups) simultaneously:

```yaml
principalMapping:
  type: COMPOSITE
  delegates:
    - type: STATIC
      userMappings:
        svc_etl: "arn:aws:iam::123456789012:role/ETLServiceRole"
      groupMappings:
        admins: "arn:aws:iam::123456789012:role/AdminRole"
      roleMappings:
        admin_role: "arn:aws:iam::123456789012:role/AdminRole"
    - type: IDENTITY_CENTER
      idcConfig:
        identityStoreId: "d-1234567890"
        region: "us-east-1"
        accountId: "123456789012"
        cacheTtlMinutes: 60
```

Delegates are evaluated in list order. A principal resolved by an earlier delegate is not passed to subsequent delegates. Constraints:

- At most one `IDENTITY_CENTER` delegate is allowed.
- `COMPOSITE` delegates cannot be nested (a delegate cannot itself be `COMPOSITE`).
- Role principals are only resolved by `STATIC` delegates — Identity Center has no role concept.

## Multi-Service Ranger Support

The plugin supports simultaneous policy sync from multiple Ranger service types. Each service type has its own adapter that maps access types and resource hierarchies to the shared Cedar/Lake Formation model.

### Supported Service Types

| Service Type | Resource Hierarchy | GDC Catalog Filtering | Output |
|---|---|---|---|
| `lakeformation` | database → table → column, data location | No | LF grants |
| `hive` | database → table → column, datalocation | No | LF grants |
| `amazon-emr-spark` | database → table → column, url | No | LF grants |
| `amazon-emr-emrfs` | sthreeresource (S3 paths) | No | S3 Access Grants |
| `presto` | catalog → schema → table → column | Yes (requires `gdcCatalogName`) | LF grants |
| `trino` | catalog → schema → table → column | Yes (requires `gdcCatalogName`) | LF grants |

For Presto and Trino, the adapter maps "schema" to "database" in the DataCatalog model and filters policies by the configured `gdcCatalogName`.

### Permission Mapping Reference

Each service adapter translates Ranger access types to Lake Formation permissions depending on the resource level at which the policy applies. The resource level is determined from the policy's resource hierarchy and may be promoted (e.g., `column=*` + `table=specific` → table level).

**Resource level promotion rules:**

| Ranger Resources | Resolved Level | LF Resource Type |
|---|---|---|
| `column=specific` + `table=specific` | column | `DataCatalog::Column` → `TABLE_WITH_COLUMNS` |
| `column=*` + `table=specific` | table | `DataCatalog::Table` |
| `column=*` + `table=*` | database | `DataCatalog::Database` |
| `table=specific` | table | `DataCatalog::Table` |
| `table=*` | database | `DataCatalog::Database` |
| `database=specific` | database | `DataCatalog::Database` |
| `url` or `datalocation` (any) | datalocation | `DataCatalog::DataLocation` |

**Gap conditions:**

| Pattern | Result |
|---|---|
| `isExcludes=true` on table resource | GAP — no "all except" in LF; policy skipped (see Future Enhancements) |
| `isExcludes=true` on column resource | GAP — LF supports `ColumnWildcard.ExcludedColumnNames` but column-level exclude is not yet implemented |
| `column=specific` + `table=*` | GAP — no LF equivalent for column on wildcard table |
| HDFS / `file://` / bare `/` paths | GAP — not convertible to LF data location |
| `s3a://` or `s3n://` prefix | Normalized to `s3://` (happy path) |
| Wildcard suffix on S3 URL (e.g., `s3://bucket/*`) | Suffix stripped → `s3://bucket/` (happy path) |

---

#### Hive

| Ranger Access Type | Cedar Action (raw) | Valid at database level | Valid at table level | Valid at column level | URL / datalocation |
|---|---|---|---|---|---|
| `select` | SELECT | — | SELECT | SELECT | DATA_LOCATION_ACCESS |
| `update` | INSERT | — | INSERT | — | DATA_LOCATION_ACCESS |
| `create` | CREATE_TABLE | CREATE_TABLE | — | — | DATA_LOCATION_ACCESS |
| `drop` | DROP | DROP | DROP | — | DATA_LOCATION_ACCESS |
| `alter` | ALTER | ALTER | ALTER | — | DATA_LOCATION_ACCESS |
| `read` | SELECT | — | SELECT | SELECT | DATA_LOCATION_ACCESS |
| `write` | INSERT | — | INSERT | — | DATA_LOCATION_ACCESS |
| `all` | ALL | ALL | ALL | ALL | DATA_LOCATION_ACCESS |
| `index` | _(unmapped)_ | — | — | — | — |
| `lock` | _(unmapped)_ | — | — | — | — |

> Data location resources (`url` / `datalocation`) always produce `DATA_LOCATION_ACCESS` regardless of the Ranger access type. LF accepts only this permission for `DataLocationResource`.

---

#### EMR Spark (`amazon-emr-spark`)

| Ranger Access Type | Cedar Action (raw) | Valid at database level | Valid at table level | Valid at column level | URL / datalocation |
|---|---|---|---|---|---|
| `select` | SELECT | — | SELECT | SELECT | DATA_LOCATION_ACCESS |
| `update` | INSERT | — | INSERT | — | DATA_LOCATION_ACCESS |
| `create` | CREATE_TABLE | CREATE_TABLE | — | — | DATA_LOCATION_ACCESS |
| `drop` | DROP | DROP | DROP | — | DATA_LOCATION_ACCESS |
| `alter` | ALTER | ALTER | ALTER | — | DATA_LOCATION_ACCESS |
| `read` | SELECT | — | SELECT | SELECT | DATA_LOCATION_ACCESS |
| `write` | INSERT | — | INSERT | — | DATA_LOCATION_ACCESS |
| `all` | ALL | ALL | ALL | ALL | DATA_LOCATION_ACCESS |

> EMR Spark uses the `url` resource type for S3 paths (not `datalocation`). The same normalization rules apply: `s3a://` and `s3n://` are rewritten to `s3://`; wildcard suffixes are stripped.

---

#### EMRFS / S3 Access Grants (`amazon-emr-emrfs`)

EMRFS policies do not interact with the Glue Data Catalog. They map S3 object operations directly to **S3 Access Grants** permissions, not Lake Formation permissions.

| Ranger Access Type | S3 Action |
|---|---|
| `GetObject` | `s3:GetObject` |
| `PutObject` | `s3:PutObject` |
| `ListObjects` | `s3:ListObjects` |
| `DeleteObject` | `s3:DeleteObject` |

> S3 Access Grants are validated separately from LF grants. The `s3AccessGrants` section in the YAML config must be configured for the converter to verify that S3 locations are registered in the Access Grants instance.

---

#### Presto (`presto`) and Trino (`trino`)

Presto and Trino use a `catalog → schema → table → column` hierarchy. The adapter maps "schema" to "database" in the Glue model and filters policies by `gdcCatalogName` (configured per service).

| Ranger Access Type | Cedar Action (raw) | Valid at schema/database level | Valid at table level | Valid at column level |
|---|---|---|---|---|
| `select` | SELECT | — | SELECT | SELECT |
| `insert` | INSERT | — | INSERT | — |
| `delete` | DELETE | — | DELETE | — |
| `create` | CREATE_TABLE | CREATE_TABLE | — | — |
| `drop` | DROP | DROP | DROP | — |
| `alter` | ALTER | ALTER | ALTER | — |
| `use` | DESCRIBE | DESCRIBE | DESCRIBE | — |
| `show` | DESCRIBE | DESCRIBE | DESCRIBE | — |
| `grant` | _(unmapped)_ | — | — | — |
| `revoke` | _(unmapped)_ | — | — | — |

> Presto and Trino have no URL/data-location resource type. All access types without a Cedar equivalent are logged and skipped.

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

This addresses two classes of drift:
- **Out-of-band changes**: console edits, other automation, or partial apply failures that leave LF in a different state than Ranger dictates
- **Orphaned permissions**: permissions that remain in LF after their Ranger source policy was deleted, including across service restarts

Configure via the `reverseSync` section in the YAML config:

| Field | Description | Default |
|-------|-------------|---------|
| `enabled` | Enable the reverse-sync feature | `false` |
| `reportOnly` | Compute drift report without applying corrections | `false` |
| `dryRun` | Serialize corrections to JSON instead of applying | `false` |
| `periodicIntervalMs` | Reserved for future independent scheduling (not currently active — see cadence note below) | `0` |
| `exclusionFilter.excludedPrincipals` | Principal ARNs to exclude from drift detection | `[]` |
| `exclusionFilter.excludedResourcePatterns` | Resource patterns to exclude | `[]` |

### Cadence

Reverse sync runs **continuously**, not just at startup. It executes in-band after every forward sync cycle at the same frequency as `policyRefreshIntervalMs` (default 30 seconds). There is currently no mechanism to run reverse sync on a separate cadence; `periodicIntervalMs` is stored and logged but not wired to an independent scheduler. Running it on a different interval (e.g., hourly) is a future enhancement.

### Safety guards

Corrective operations are ordered REVOKEs-first to avoid transient over-permissioning. Individual failures are logged to the dead-letter log without aborting the remaining batch. An empty Cedar policy set skips the entire cycle to prevent mass revocation before the first forward sync cycle completes.

## Future Enhancements

### Table-Level `isExcludes` (Exclude-Pattern Sync)

Ranger supports a policy pattern where a table list with `isExcludes=true` means "all tables in this database **except** the listed ones, including tables added in the future." For example:

- Database = `analytics`, Tables = `[pii_table, restricted_table]` with `isExcludes=true`, Columns = `*`

This effectively grants access to every current and future table in `analytics` except the two named ones.

Lake Formation has no native `ExcludedTableNames` concept, but the sync loop already implements wildcard table expansion (resolving `db.*` to explicit table grants each cycle). The same mechanism could support table-level excludes:

1. Each sync cycle, resolve all tables in the database from the Glue catalog.
2. Subtract the excluded table list.
3. Issue explicit LF grants for the remaining tables.
4. When new tables are added to the database, the next sync cycle automatically picks them up and grants access — matching Ranger's forward-looking semantics.

This approach is feasible but adds complexity: the exclude list must be stored and diffed each cycle, and stale grants for tables that are later added to the exclude list must be revoked promptly. Currently these policies are skipped and recorded as `EXCLUDES_PATTERN` gaps. Implementation is tracked as a future enhancement.

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

## Pre-Migration Assessment Tool

Before setting up the full sync pipeline, run the assessment tool to understand how your existing Ranger policies will migrate. It fetches policies from Ranger Admin or a file export, runs the complete conversion pipeline in read-only mode (no AWS Lake Formation calls are made), and produces a console summary plus an optional JSON report.

### Building

```bash
mvn clean package -DskipTests
```

This produces `target/assessment-jar-with-dependencies.jar` in addition to the main server JAR.

### Usage

```
server [<config-file>] [options]
  --ranger-url <url>               Ranger Admin URL (required if no config file)
  --ranger-user <user>             Ranger Admin username
  --ranger-password <pass>         Ranger Admin password
  --services <s1,s2,...>           Comma-separated service instance names to assess
  --output-dir <dir>               Directory for JSON report (default: current dir)
  --aws-region <region>            Enable Glue wildcard expansion with this region
  --aws-profile <profile>          AWS credentials profile for Glue/STS calls
  --output-lf-policies-path <path> Write projected LF permission operations to this JSON file
  --output-gaps-path <path>        Write partially/not-convertible policies with gap details to this JSON file
  --console-only                   Print report to console only, skip JSON file
  --skip-validation                Skip Cedar schema validation (required for large policy sets)

file <export-file.json> [options]
  --output-dir <dir>               Directory for JSON report (default: current dir)
  --aws-region <region>            Enable Glue wildcard expansion with this region
  --aws-profile <profile>          AWS credentials profile for Glue/STS calls
  --output-lf-policies-path <path> Write projected LF permission operations to this JSON file
  --output-gaps-path <path>        Write partially/not-convertible policies with gap details to this JSON file
  --console-only                   Print report to console only, skip JSON file
  --skip-validation                Skip Cedar schema validation (required for large policy sets)
```

### Obtaining a Ranger Export File

In the Ranger Admin UI, navigate to the service policy list and use the **Export** button. Select **Export Type: JSON** and save the file. If your browser downloads a ZIP archive, unzip it first — only the `.json` file is supported.

### Examples

**Quickstart — CLI flags only (no AWS credentials needed):**

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  server \
  --ranger-url http://ranger-admin:6080 \
  --ranger-user admin \
  --ranger-password rangerR0cks! \
  --console-only
```

**Using an existing config file as the base (CLI flags override individual values):**

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  server /path/to/server-config.yaml \
  --console-only
```

**Assess specific services:**

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  server \
  --ranger-url http://ranger-admin:6080 \
  --ranger-user admin \
  --ranger-password rangerR0cks! \
  --services hive_prod,lakeformation_prod \
  --output-dir ./assessment-results
```

**With Glue wildcard expansion (requires AWS credentials):**

```bash
# Using a named AWS profile
java -jar target/assessment-jar-with-dependencies.jar \
  server \
  --ranger-url http://ranger-admin:6080 \
  --ranger-user admin \
  --ranger-password rangerR0cks! \
  --aws-region us-east-1 \
  --aws-profile my-profile

# Using credentials already in the environment (env vars, instance profile, etc.)
java -jar target/assessment-jar-with-dependencies.jar \
  server \
  --ranger-url http://ranger-admin:6080 \
  --ranger-user admin \
  --ranger-password rangerR0cks! \
  --aws-region us-east-1
```

**Assess from a Ranger export file (no Ranger Admin or AWS credentials needed):**

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  file ./ranger-export.json \
  --console-only
```

**Output projected LF operations to a file (one-shot apply inspection):**

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  file ./ranger-export.json \
  --output-lf-policies-path ./lf-operations.json \
  --console-only
```

The file contains a JSON array of Lake Formation `GRANT` operations — the same structure the sync server applies at runtime. With no `--aws-region` or principal mapping configured, principals appear as `ranger-user:alice` style placeholders and wildcard resources are unexpanded; re-run with those options to get real ARNs and fully resolved resource names.

**Output a gap report for partially and non-convertible policies:**

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  file ./ranger-export.json \
  --output-gaps-path ./gaps.json \
  --console-only
```

The file contains one entry per policy classified as `PARTIALLY_CONVERTIBLE` or `NOT_CONVERTIBLE`, each with a `gaps` array describing the specific unsupported features and recommendations. Informational gaps (`WILDCARD_PATTERN`, `CANNOT_VALIDATE_S3_LOCATION`) are excluded so the file focuses on actionable items.

**Large policy files (>~10,000 policies):**

For large exports, Cedar schema validation exhausts JVM heap memory. Use `--skip-validation` and increase the heap size:

```bash
java -Xmx4g -jar target/assessment-jar-with-dependencies.jar \
  file ./ranger-export.json \
  --console-only \
  --skip-validation
```

When `--skip-validation` is set, Cedar statements are parsed but not validated against the schema. Gap detection and convertibility counts remain accurate; only per-statement schema errors are suppressed.

When `--aws-region` is provided, the tool queries the Glue Data Catalog to expand wildcard resource patterns (e.g., `db_*`) into explicit names before counting projected grants. Without it, wildcards are reported as-is and counted as `WILDCARD_PATTERN` gaps if they cannot be resolved.

Use `--aws-profile` to specify which `~/.aws/credentials` profile to use for Glue and STS calls. Without it, the default AWS credential chain is used (environment variables, `~/.aws/credentials` default profile, EC2/ECS instance profile, etc.).

#### Principal Mapping in Assessment Mode

When you run `file` without a config file, the tool has no information about how
Ranger usernames map to IAM ARNs. In this case it automatically uses a **passthrough mapper**
that echoes Ranger names as placeholder identifiers:

| Ranger principal | Placeholder in report |
|---|---|
| user `alice` | `ranger-user:alice` |
| group `analysts` | `ranger-group:analysts` |
| role `admin` | `ranger-role:admin` |

This lets the tool measure structural convertibility (which policies have gaps) without
requiring IAM configuration upfront. A warning banner is printed at the top of the report:

```
⚠  No principal mapping configured. Ranger usernames are passed through as-is
   (e.g. "ranger-user:alice", "ranger-group:analysts"). Re-run with a config file
   that includes a principalMapping section to produce accurate LF grant output.
```

Once you know which policies are convertible, add a `principalMapping` section to your
config file and re-run with `server` to generate accurate LF grant output.

### Sample Console Output

```
=== Apache Ranger → Lake Formation Assessment ===
Source:       ranger-admin:http://ranger-admin:6080
Assessed at:  2024-06-01T10:30:00Z

Services assessed:
  lf_prod              (lakeformation)  — assessed  (31 policies)
  hive_prod            (hive)           — assessed  (16 policies)

Policies scanned:           47
  Fully convertible:        31 (66%)
  Partially convertible:    10 (21%)
  Not convertible:           6 (13%)
Projected LF grants:       142

Gaps detected (23 total):
  DATA_MASKING             :   5  — LF has no column masking. Consider removing or migrating to row-level filters.
  DENY_POLICY              :   8  — Deny rules emit Cedar forbid statements; review carefully before applying.
  VALIDITY_SCHEDULE        :   3  — Time-bound access control is not supported in LF.
  CUSTOM_CONDITION         :   2  — Attribute-based conditions cannot be expressed in LF permissions.
  SECURITY_ZONE            :   2  — Ranger Security Zones have no equivalent in Lake Formation.
  TAG_BASED_POLICY         :   3  — Tag-based policies cannot be expressed as LF resource permissions.

Full report written to: ./assessment-report-2024-06-01T10-30-00Z.json
```

### JSON Report Format

```json
{
  "source": "ranger-admin:http://ranger-admin:6080",
  "services": [
    { "name": "lf_prod", "serviceType": "lakeformation", "status": "assessed", "policiesScanned": 31 }
  ],
  "warnings": [
    "No principal mapping configured. Ranger usernames are passed through as-is ..."
  ],
  "totalPolicies": 47,
  "fullyConvertible": 31,
  "partiallyConvertible": 10,
  "notConvertible": 6,
  "projectedGrantCount": 142,
  "gapReport": {
    "entries": [
      {
        "policyId": "42",
        "policyName": "mask-ssn",
        "gapType": "DATA_MASKING",
        "resourcePath": "hr_db/employees",
        "details": "Data masking policy (policyType=1) cannot be represented in Cedar.",
        "recommendation": "Consider using column-level permissions or external masking solutions."
      }
    ],
    "summary": {
      "DATA_MASKING": 5,
      "DENY_POLICY": 8
    },
    "generatedAt": "2024-06-01T10:30:00Z"
  }
}
```

`warnings` is omitted when the list is empty (configured runs are unaffected).

### Convertibility Classification

| Category | Meaning |
|----------|---------|
| **Fully convertible** | Policy produces at least one projected LF grant and has zero gap entries |
| **Partially convertible** | Policy produces at least one projected LF grant but also has one or more gap entries (some features will be silently dropped) |
| **Not convertible** | Policy produces zero projected LF grants (entirely skipped — all access types unmapped, no principal mappings, or tag/masking policy type) |

### Gap Types

| Gap Type | Description |
|----------|-------------|
| `DATA_MASKING` | Policy type 1 (data masking). The entire policy is skipped — LF has no native column masking. |
| `TAG_BASED_POLICY` | Service name contains `"tag"`. The entire policy is skipped — conversion to LF-Tag permissions is not yet implemented. Detection relies on a service-name heuristic, not a structural `policyType` check. |
| `DENY_POLICY` | Policy has deny items. Deny items are converted to Cedar `forbid` statements but cannot be expressed in LF (grant-only model). |
| `DENY_EXCEPTION` | Policy has deny exceptions. Converted to Cedar `permit @denyException` statements but not enforceable in LF. |
| `VALIDITY_SCHEDULE` | Policy has time-based validity schedules. LF does not support temporal constraints; schedules are dropped. |
| `CUSTOM_CONDITION` | Policy has attribute-based conditions. LF has no conditional permission model; conditions are dropped. |
| `SECURITY_ZONE` | Policy is scoped to a Ranger Security Zone. LF has no equivalent; the zone attribute is ignored. |
| `DELEGATED_ADMIN` | Policy item has `delegateAdmin=true`. Partially supported — the flag is recorded but no `WITH GRANT OPTION` is applied to the resulting LF grants. |
| `WILDCARD_PATTERN` | Resource pattern contains wildcards and could not be expanded (no AWS credentials). **Note:** this gap type is defined but is not currently emitted at runtime — wildcard expansion failures produce a log warning only. |
| `UNSUPPORTED_SERVICE_TYPE` | No adapter registered for this Ranger service type. In `server` subcommand mode, the entire policy is skipped. In `file` subcommand mode, one gap entry is recorded per skipped service (not per policy), and the `details` field includes the count of bypassed policies. |
| `UNSUPPORTED_ACTION` | One or more access types have no LF permission mapping. The unsupported action is dropped; other actions in the policy continue. |
| `UNMAPPED_RESOURCE` | Resource ID cannot be mapped to an LF resource. The resource is skipped. |
| `SCHEMA_VALIDATION_FAILURE` | A Cedar statement failed schema validation and was excluded. Valid statements in the same policy are retained. |
| `UNREGISTERED_S3_LOCATION` | An S3 prefix referenced by the policy is not covered by a registered Lake Formation data location. Reported by the assessment tool when S3 Access Grants validation is enabled. |
| `CANNOT_VALIDATE_S3_LOCATION` | S3 location validation was requested but no S3 Access Grants configuration is present. Reported by the assessment tool. |

### Architecture Note

The assessment tool reuses the production `RangerToCedarConverter` and `CedarToLFConverter` pipeline unchanged. Any new service types, access type mappings, or gap detection logic added to the sync pipeline automatically improve assessment accuracy with no changes to the assessment tool.

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
| `app` | Entry points (`ConversionServerMain`, `SyncServiceMain`, `ServiceDefInstallerMain`, `AssessmentMain`), process lifecycle (`ServerLifecycle`), sync cycle execution (`SyncCycleExecutor`), and wildcard refresh scheduling (`WildcardRefreshScheduler`) |
| `assessment` | One-time gap assessment tool: `AssessmentConfig`, `AssessmentRunner`, `AssessmentResult`, `AssessmentReporter` |
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

## Real-World Simulator

The simulator is a standalone long-running tool that continuously mutates Ranger policies at random, waits for the sync service to apply them to Lake Formation, and then independently validates that LF permissions are exactly correct. It is the primary tool for catching bugs in the sync pipeline before they affect production.

See **[simulator/README.md](simulator/README.md)** for the full guide, including:

- Step-by-step AWS account setup (IAM roles, LakeFormation managed mode, Glue databases/tables)
- Configuration reference
- How to run the simulator
- How to read the output and interpret violations
- Reproduction bundle format
- Complete list of test cases covered and known coverage gaps

## Docker Container

The project ships two Dockerfiles:

- **`Dockerfile`** — multi-stage build used for production deployment. Runs `mvn package` inside the image to produce the fat JAR, then packages it into an `eclipse-temurin:17-jre` runtime image.
- **`integration-test/docker/Dockerfile.it`** — single-stage runtime image used for integration tests. Accepts the pre-built fat JAR via `--build-arg JAR_FILE=` to avoid a slow inner Maven build on every test run.

Both containers:
- Use `SIGTERM` as the stop signal for graceful shutdown
- Include a `HEALTHCHECK` via `pgrep`
- Accept a config file path as the entrypoint argument

```bash
# Production image
docker build -t ranger-lf-sync .
docker run -v /path/to/config.yaml:/app/config.yaml ranger-lf-sync

# Integration test image (built automatically by mvn verify -Pintegration-test)
docker build -f integration-test/docker/Dockerfile.it \
  --build-arg "JAR_FILE=target/ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
  -t ranger-lf-it:local .
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
| `UnmappedPrincipal` | Count (1) | Principal cannot be resolved (includes `PrincipalType=user\|group\|role` dimension) |

## License

Apache License 2.0
