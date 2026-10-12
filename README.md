
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
| IDC mapper does not support roles | Limitation | When using the Identity Center principal mapper, Ranger role principals always produce an empty mapping and are silently skipped with an `UnmappedPrincipal` metric. Use the static mapper if you need role-to-IAM-ARN mappings. |
| Ranger policy for a deleted Glue resource | Handled — see [Deleted Glue Resources](#deleted-glue-resources) | Lake Formation auto-removes permissions when a table/database is deleted. A Ranger policy still referencing it produces a grant that fails with "Table/Database not found"; the sync service skips it (no dead-letter), retries each cycle (self-heals if the resource is recreated), and publishes the `MissingResourceGrants` gauge so you can prune stale policies. |
| Access types with no LF equivalent by design | Informational — see `KNOWN_UNMAPPABLE_ACTION` in [Gap Types](#gap-types) | Hive `index`/`lock`/`refresh` and admin types (`repladmin`/`serviceadmin`/`tempudfadmin`); Presto/Trino `grant`/`revoke`/`execute`/`impersonate`. These are recognised but deliberately dropped — Lake Formation has no equivalent concept — and never demote a policy's convertibility. Exact per-access-type occurrence counts and sample policy IDs are in the assessment tool's `knownUnmappableActions` rollup, not the per-policy gap entries. |

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

## Deleted Glue Resources

When a Glue table or database is deleted from the Data Catalog, **Lake Formation automatically
removes all permissions on that resource** — the sync service does not need to (and cannot) revoke
them. However, the Ranger policy that referenced the resource typically remains (Ranger does not
track Glue resource lifecycle). On the next sync cycle the service therefore attempts to re-grant
the permission and Lake Formation rejects it with `InvalidInputException: "Table not found."` (or
`"Database not found."`).

The sync service treats this as an **expected, benign condition** rather than an error:

- **Not dead-lettered, not error-logged.** A single INFO line is emitted per affected policy per
  cycle. This is distinct from a genuine grant failure (access denied, throttling, etc.), which is
  still error-logged and dead-lettered.
- **Retried each cycle.** The grant is kept out of the checkpoint so it is re-attempted on every
  cycle. If the table/database is later **recreated** in Glue, the grant succeeds automatically on
  the next cycle — no manual intervention and no Ranger policy change required. This is why the
  service intentionally does *not* give up permanently.
- **Surfaced via a metric.** Each cycle publishes the `MissingResourceGrants` CloudWatch gauge —
  the current count of distinct Ranger policies whose grant targets a resource that no longer
  exists (published as `0` when there are none, so it reflects current state, not a running total).

**Operational guidance:** a sustained non-zero `MissingResourceGrants` value means one or more
Ranger policies reference Glue resources that have been deleted. If the resource is not going to be
recreated, **clean up the corresponding Ranger policy** to stop the per-cycle retry. The metric is
the intended signal for this cleanup. Alarm on a sustained non-zero value if you want proactive
notification of stale policies.

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

Presto and Trino services require a `gdcCatalogName` property identifying which catalog maps to the Glue Data Catalog.

#### Which catalog values are in scope

A Presto/Trino policy's `catalog` resource is considered in scope when **any** of its values names the Glue Data Catalog this tool writes to:

| Policy `catalog` value | In scope | Why |
|---|---|---|
| the configured `gdcCatalogName` | yes | whatever the Trino cluster calls the Glue catalog |
| `awsdatacatalog` (any case) | yes | conventional alias for the account's root catalog |
| the AWS account id, or the configured `catalogId` | yes | how the root catalog is named in Glue ARNs and LF grants |
| `*` | yes — see below | |
| anything else (`system`, `postgres`, a connector catalog) | no | reported as a `CATALOG_NOT_IN_SCOPE` gap |

Every value is checked, not just the first, so a policy listing `["postgres", "awsdatacatalog"]` is in scope.

**`catalog=*` resolves to the default (root) catalog.** This is a deliberate simplification. A Ranger
wildcard means "every catalog this Trino cluster exposes", which can include connector catalogs
(`postgres`, `memory`, `system`) that Lake Formation does not govern. Glue currently exposes only the
account's root catalog to this tool — nothing enumerates catalogs, and Glue calls omit `catalogId` so
they implicitly target the root — so granting on the root catalog is the faithful part of the
translation and the remainder was never Lake-Formation-governable.

This will need revisiting when Glue Data Catalog supports multiple user-defined catalogs: at that
point `*` should resolve against the catalogs that actually exist rather than assuming the root, and
a policy naming a catalog that does not exist *yet* arguably should be retained and granted if that
catalog appears later. Tracked as a backlog item.

A policy skipped for being out of scope produces zero Lake Formation grants and is classified
`NOT_CONVERTIBLE`, with a `CATALOG_NOT_IN_SCOPE` gap naming the catalogs it targeted. Earlier
versions skipped silently, which made such policies look inexplicably unconvertible.

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
| `column=*` + `table=*` | table (wildcard) | `DataCatalog::Table` (ARN `table/{db}/*`) → LF `TableWildcard` |
| `table=specific` | table | `DataCatalog::Table` |
| `table=*` (no column key) | table (wildcard) | `DataCatalog::Table` (ARN `table/{db}/*`) → LF `TableWildcard` |
| `database=specific` | database | `DataCatalog::Database` |
| `url` or `datalocation` (any) | datalocation | `DataCatalog::DataLocation` |

A bare `table=*` — with no column restriction, or with `column=*` alongside it — is never Glue-expanded and never promoted to database level. It maps directly to a single Lake Formation `TableWildcard` grant covering every table in the database: one grant instead of one per concrete table, and tables created later are covered natively and atomically rather than waiting for the next `wildcardRefreshIntervalSeconds` refresh cycle (see below).

**Gap conditions:**

| Pattern | Result |
|---|---|
| `isExcludes=true` on table resource | GAP — no "all except" in LF; policy skipped (see Future Enhancements) |
| `isExcludes=true` on column resource | GAP — LF supports `ColumnWildcard.ExcludedColumnNames` but column-level exclude is not yet implemented |
| `column=specific` + `table=*` | GAP — Lake Formation's `TableWildcard` is table-level only and cannot carry a column restriction; granting it anyway would expose every column of every table in the database, so no grant is produced |
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

| Ranger Access Type | Cedar Action (raw) | Valid at schema/database level | Valid at table level | Valid at column level | URL (`external_location`) |
|---|---|---|---|---|---|
| `select` | SELECT | — | SELECT | SELECT | DATA_LOCATION_ACCESS |
| `insert` | INSERT | — | INSERT | — | DATA_LOCATION_ACCESS |
| `delete` | DELETE | — | DELETE | — | — |
| `create` | CREATE_TABLE | CREATE_TABLE | — | — | — |
| `drop` | DROP | DROP | DROP | — | — |
| `alter` | ALTER | ALTER | ALTER | — | — |
| `use` | DESCRIBE | DESCRIBE | DESCRIBE | — | — |
| `show` | DESCRIBE | DESCRIBE | DESCRIBE | — | — |
| `read` | SELECT | — | SELECT | SELECT | DATA_LOCATION_ACCESS |
| `write` | INSERT | — | INSERT | — | DATA_LOCATION_ACCESS |
| `update` | INSERT | — | INSERT | — | DATA_LOCATION_ACCESS |
| `all` | ALL | ALL | ALL | ALL | DATA_LOCATION_ACCESS |
| `grant` | _(known-unmappable)_ | — | — | — | — |
| `revoke` | _(known-unmappable)_ | — | — | — | — |
| `execute` | _(known-unmappable)_ | — | — | — | — |
| `impersonate` | _(known-unmappable)_ | — | — | — | — |

> Presto and Trino **do** support a `url` resource (Presto/Trino `external_location`), which
> always collapses to `DATA_LOCATION_ACCESS` — the same behavior as Hive's `url`/`datalocation`
> resources, since Lake Formation accepts only that one permission on a data location.
>
> `grant`, `revoke`, `execute`, and `impersonate` are recognised but deliberately unmapped —
> Lake Formation has no equivalent concept for engine-level delegation, procedure execution, or
> user impersonation — and are reported as `KNOWN_UNMAPPABLE_ACTION`, not a plain unmapped/dropped
> action. An access type neither adapter recognises at all is reported as `UNSUPPORTED_ACTION`
> instead and does demote the policy.
>
> Presto/Trino resource keys with no Glue/LF shape at all — `procedure`, `function`, `trinouser`,
> `sessionproperty`, `systemproperty` — are gapped explicitly rather than silently defaulted to
> the database level.

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

When Ranger policies use glob patterns that match a subset of table names (e.g., `table_*`, `db_?`), the system expands them against the Glue Data Catalog at conversion time. The bare `*` wildcard (ALL tables in a database) is never expanded against Glue at all — it is handled natively by Lake Formation's `TableWildcard`, a single grant that covers every table in the database, including ones created later.

For partial glob patterns that match a subset of resources, a periodic refresh scheduler re-evaluates them against the current catalog state. When new resources appear (or old ones disappear) that match existing patterns, the system computes a delta and applies the necessary grant/revoke operations. A bare `*` wildcard does not participate in this refresh cycle — it needs no delta computation, since the `TableWildcard` grant already covers tables added or removed at any time.

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
  --log-level <level>              TRACE|DEBUG|INFO|WARN|ERROR (default INFO)
  --log-file <path>                Also write a plain-text log to this file
  --skip-validation                Skip Cedar schema validation (required for large policy sets)

file <export-file.json> [options]
  --output-dir <dir>               Directory for JSON report (default: current dir)
  --aws-region <region>            Enable Glue wildcard expansion with this region
  --aws-profile <profile>          AWS credentials profile for Glue/STS calls
  --output-lf-policies-path <path> Write projected LF permission operations to this JSON file
  --output-gaps-path <path>        Write partially/not-convertible policies with gap details to this JSON file
  --console-only                   Print report to console only, skip JSON file
  --log-level <level>              TRACE|DEBUG|INFO|WARN|ERROR (default INFO)
  --log-file <path>                Also write a plain-text log to this file
  --skip-validation                Skip Cedar schema validation (required for large policy sets)
```

### Diagnostic logging

By default the tool logs at `INFO` to the console **and** to a rolling JSON file at
`logs/ranger-lf-sync.log` (configurable via the `LOG_DIR` environment variable or the `log.dir`
system property). That default file exists whether or not you pass any logging flag — if you are
chasing a problem on someone else's run, it is the first place to look.

Two flags give you control without needing a custom logback configuration:

```bash
java -jar target/assessment-jar-with-dependencies.jar \
  file ./ranger-export.json \
  --log-level DEBUG \
  --log-file ./assessment.log \
  --console-only
```

| Flag | Effect |
|---|---|
| `--log-level <level>` | Overrides the `INFO` default. An unrecognised level fails fast rather than silently falling back. |
| `--log-file <path>` | Adds a plain-text (non-JSON, non-rolling) log at `path`, in addition to the console and the default rolling file. Truncates on each run, so one file is one run. An unwritable path fails fast. |

**What `DEBUG` adds that matters.** Several conditions are deliberately logged at `DEBUG` because
they are expected outcomes rather than problems, and logging them at an operator-facing level
produced dozens of identical lines per policy:

- each individual occurrence of an unmappable access type (the operator-facing summary is one
  aggregated `ERROR` per conversion cycle — see `UNSUPPORTED_ACTION`);
- access types deliberately dropped by design (`KNOWN_UNMAPPABLE_ACTION`);
- access types narrowed to a weaker permission (`NARROWED_ACTION`);
- Presto/Trino policies skipped because their catalog is out of scope.

So if a policy is reported `NOT_CONVERTIBLE` and the gap report does not explain it to your
satisfaction, re-running at `DEBUG` with `--log-file` will show the per-policy decisions behind it.
Expect substantial output on a large export — this is for targeted investigation, not routine runs.

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

> **Breaking change:** as of this release, `--output-gaps-path` writes a JSON **object**, not a
> bare array. Previously the file was `[ {PolicyGapSummary}, ... ]`. It is now
> `{"policies": [...], "knownUnmappableActions": [...], "narrowedActions": [...]}`. Nothing in
> this repository reads the file back, so this only affects external tooling that parses it —
> update any such scripts to read `.policies` instead of the top-level array.

The `policies` array contains one entry per policy classified as `PARTIALLY_CONVERTIBLE` or
`NOT_CONVERTIBLE`, each with a `gaps` array describing the specific unsupported features and
recommendations. `WILDCARD_PATTERN` and `CANNOT_VALIDATE_S3_LOCATION` entries are excluded from
each policy's `gaps` array so the file focuses on actionable items — a policy whose only gap was
one of those two types can appear with an empty `gaps` array. `KNOWN_UNMAPPABLE_ACTION` and
`NARROWED_ACTION` entries *are* kept per-policy (they're informational but still worth surfacing
per policy) — though in practice a policy affected *only* by narrowing is fully convertible and
so will not appear in `policies` at all; the `narrowedActions` rollup is its only trace here.

`knownUnmappableActions` is the per-`(serviceType, accessType)` rollup for access types dropped
entirely — see [Gap Types](#gap-types) below — and is omitted entirely when empty.

`narrowedActions` is the equivalent rollup for access types that were mapped but narrowed to a
weaker LF permission (e.g. column-scoped `all` → `SELECT`); each row also carries `narrowedTo`
(the permission actually granted) and `reason` (why LF could not grant the broader permission).
Also omitted entirely when empty.

```json
{
  "policies": [
    {
      "policyId": "1024",
      "policyName": "raw-unrecognised-access-type",
      "convertibility": "NOT_CONVERTIBLE",
      "gaps": [
        {
          "gapType": "UNSUPPORTED_ACTION",
          "details": "Access type(s) notarealprivilege have no Cedar mapping for service 'hive' and will be skipped.",
          "recommendation": "Review the Ranger policy and remove or replace the unsupported access type(s)."
        }
      ]
    },
    {
      "policyId": "1022",
      "policyName": null,
      "convertibility": "PARTIALLY_CONVERTIBLE",
      "gaps": [
        {
          "gapType": "KNOWN_UNMAPPABLE_ACTION",
          "serviceType": "hive",
          "accessTypes": ["index", "lock"],
          "details": "..."
        }
      ]
    }
  ],
  "knownUnmappableActions": [
    {
      "serviceType": "hive",
      "accessType": "index",
      "occurrenceCount": 1,
      "samplePolicyIds": ["1022"]
    }
  ],
  "narrowedActions": [
    {
      "serviceType": "hive",
      "accessType": "all",
      "occurrenceCount": 1,
      "samplePolicyIds": ["9001"],
      "narrowedTo": "SELECT",
      "reason": "Lake Formation accepts only SELECT on column-restricted (TABLE_WITH_COLUMNS) resources."
    }
  ]
}
```

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

Captured from the demo (`demo/Demo_Ranger_Policies.json`, run with
`--output-lf-policies-path` / `--output-gaps-path`, no AWS credentials):

```
=== Apache Ranger → Lake Formation Assessment ===
Source:       file:Demo_Ranger_Policies.json
Assessed at:  2026-09-18T18:02:03Z

Services assessed:
  acme_hive            (hive            ) — assessed  (86 policies)
  acme_trino           (trino           ) — assessed  (56 policies)
  acme_emrfs           (amazon-emr-emrfs) — assessed  (30 policies)
  acme_tags            (tag             ) — skipped: unsupported service type

Policies scanned:          172
  Fully convertible:       157 (91%)
  Partially convertible:     5 (3%)
  Not convertible:          10 (6%)
Projected LF grants:       201

Gaps detected (65 total):
  DATA_MASKING                :   1  — LF has no column masking. Consider removing or migrating to row-level filters.
  DENY_POLICY                 :   3  — Deny rules are emitted as Cedar forbid statements; review carefully before applying.
  DENY_EXCEPTION              :   1  — Deny exceptions are emitted as Cedar deny-exception permits; verify intended access.
  VALIDITY_SCHEDULE           :   1  — Time-bound access control is not supported in LF permissions.
  CUSTOM_CONDITION            :   1  — Attribute-based conditions cannot be expressed as LF permissions.
  SECURITY_ZONE               :   1  — Ranger Security Zones have no equivalent in Lake Formation.
  WILDCARD_PATTERN            :  14  — Wildcard patterns were not expanded (no AWS credentials); resources treated as-is.
  UNSUPPORTED_SERVICE_TYPE    :   1  — No adapter registered for this Ranger service type; policies will be skipped.
  UNSUPPORTED_ACTION          :   1  — One or more Ranger access types have no LF permission equivalent and will be dropped.
  KNOWN_UNMAPPABLE_ACTION     :   6  — Access types with no Lake Formation equivalent by design; dropped, and not counted against convertibility. See the rollup below.
  UNMAPPED_RESOURCE           :   2  — Resource paths cannot be mapped to LF data locations (HDFS/file:// not supported; see details below).
  SCHEMA_VALIDATION_FAILURE   :   1  — A Cedar statement failed schema validation and was excluded from conversion.
  CANNOT_VALIDATE_S3_LOCATION :  31  — No S3 Access Grants configuration provided. Cannot validate whether S3 locations are registered. Add s3AccessGrants config to enable validation.
  EXCLUDES_PATTERN            :   1  — isExcludes=true ("all except") patterns have no Lake Formation equivalent; these policies are skipped.

Unmapped resource paths (2) — convert to S3 URIs or ignore:
  [1025] hdfs://acme-nn01:8020/staging/inbound/
  [1025] hdfs://acme-nn01:8020/staging/inbound/

Known-unmappable access types (informational — no Lake Formation equivalent, no effect on convertibility):
  hive: repladmin          —       1 policy
  hive: serviceadmin       —       1 policy
  hive: index              —       1 policy
  hive: lock               —       1 policy
  trino: grant             —       1 policy
  trino: revoke            —       1 policy

Policies with gaps (20):
  Policy: id=null
    [UNSUPPORTED_SERVICE_TYPE] Service 'acme_tags' (serviceType='tag') has no registered adapter. All 2 policies in this service are skipped.
      → Supported service types are: lakeformation, hive, presto, trino, amazon-emr-emrfs.
    [CANNOT_VALIDATE_S3_LOCATION] No s3AccessGrants config provided; cannot check location registration for s3://acme-datalake-curated/daily_sales/*
      → null
    ... (28 more CANNOT_VALIDATE_S3_LOCATION entries, one per EMRFS S3 prefix)
  Policy: id=1004
    Resource: acme_refined/*
    [WILDCARD_PATTERN] Table pattern '*' in database 'acme_refined' could not be expanded (no Glue catalog access). The ARN produced is a placeholder.
      → Re-run with AWS credentials configured to expand wildcard table patterns.
    [WILDCARD_PATTERN] Column pattern '*' in acme_refined.* could not be expanded (no Glue catalog access). The ARN produced is a placeholder.
      → Re-run with AWS credentials configured to expand wildcard column patterns.
  ...
  Policy: mask-employee-ssn (id=1016)
    Resource: hr/employees/ssn
    [DATA_MASKING] Data masking policy (policyType=1) cannot be represented in Cedar.
      → Consider using column-level permissions or external masking solutions.
  ...
  Policy: raw-unrecognised-access-type (id=1024)
    [UNSUPPORTED_ACTION] Access type(s) notarealprivilege have no Cedar mapping for service 'hive' and will be skipped.
      → Review the Ranger policy and remove or replace the unsupported access type(s).
  Policy: id=1025
    Resource: hdfs://acme-nn01:8020/staging/inbound/
    [UNMAPPED_RESOURCE] Location 'hdfs://acme-nn01:8020/staging/inbound/' uses an unsupported scheme (hdfs://) and cannot be mapped to a Lake Formation data location.
      → Convert HDFS/file paths to S3 URIs (s3://) and re-run the assessment.
    [UNMAPPED_RESOURCE] Policy resources could not be resolved to any Lake Formation resource after wildcard/catalog expansion (resource level 'url'). The policy produces no LF grants.
      → Verify the policy's resource keys match the service definition and that any wildcard patterns resolve against the Glue catalog.
  ... (9 more policies with gaps — deny/deny-exception, validity schedule, custom condition,
       security zone, excludes-pattern, schema-validation-failure; see demo/README.md for the
       full annotated walkthrough)

LF permission operations written to: /path/to/demo/out/lf_operations.json
Gap report written to: /path/to/demo/out/gaps.json
```

Note the `raw-unrecognised-access-type` policy (id=1024) above: `notarealprivilege` is not a
type any Hive adapter recognises at all, so it is reported as `UNSUPPORTED_ACTION` and the
policy is demoted — contrast with the six `KNOWN_UNMAPPABLE_ACTION` rows in the rollup, which
never demote.

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
      },
      {
        "gapType": "KNOWN_UNMAPPABLE_ACTION",
        "serviceType": "hive",
        "accessTypes": ["index"],
        "details": "Access type 'index' has no Lake Formation equivalent for service type 'hive' and was dropped in 1 policy item(s).",
        "recommendation": "No action required unless this capability is needed — Lake Formation does not model it. Remove the access type from the Ranger policies to silence this."
      }
    ],
    "summary": {
      "DATA_MASKING": 5,
      "DENY_POLICY": 8,
      "KNOWN_UNMAPPABLE_ACTION": 1
    },
    "generatedAt": "2024-06-01T10:30:00Z"
  },
  "knownUnmappableActions": [
    {
      "serviceType": "hive",
      "accessType": "index",
      "occurrenceCount": 1,
      "samplePolicyIds": ["1022"]
    }
  ]
}
```

`warnings` is omitted when the list is empty (configured runs are unaffected).
`knownUnmappableActions` is omitted when there are none.

**`KNOWN_UNMAPPABLE_ACTION` entries in `gapReport` are collapsed**, not per-policy: every
per-policy occurrence of a deliberately-unmapped access type (e.g. Hive `index`) is replaced by
a single entry per `(serviceType, accessType)` with `policyId: null`, before the report is
written. This keeps the file bounded on large estates — a real customer export can carry
133,000 policies, and hundreds of thousands of identical per-policy entries would dominate the
file. A consequence worth knowing: because `gapReport.summary` is a count of *entries*, the
`KNOWN_UNMAPPABLE_ACTION` number there is the count of **distinct access types** affected, not
the number of policies or occurrences. The exact occurrence count per access type lives in
`knownUnmappableActions[].occurrenceCount`, with up to 10 sample policy IDs in
`samplePolicyIds`.

### Unmappable principals

A Ranger policy names users, groups and roles; each must be mapped to an AWS identity before a Lake
Formation grant can be issued. When a principal cannot be mapped, no grant is produced for it — by
the sync server as well as the assessment tool — so the assessment reports it rather than failing.

Every unmappable principal is listed once at the end of the report with its cause, the number of
policies naming it, and sample policy ids:

```
Unmappable principals (3) — no grants were produced for these:
  directory lookup failed (1)
     → The assessment could not determine this principal's identity, so its grant counts are
       unreliable. Check the log for the underlying error and re-run.
    group:analysts                     14 policies, e.g. 1476, 1483, 1487
  no mapping configured (1)
     → Add the principal to principalMapping (or enable a mapper that can resolve it) and re-run;
       real access is otherwise under-reported.
    user:svc-etl                        8 policies, e.g. 1473, 1477, 1479
  not found in directory (1)
     → Stale Ranger policy — consider pruning it, or re-create the principal.
    user:departed_dev                   2 policies, e.g. 1519, 1574
```

The three causes need different responses, which is why they are reported separately rather than as
one "unmapped" count:

| Cause | Meaning | What to do |
|---|---|---|
| `NOT_FOUND` | the directory was queried and confirmed the principal does not exist | stale Ranger policy — prune it, or re-create the principal |
| `NO_MAPPING` | no mapping is configured, so the principal was never looked up | add it to `principalMapping` and re-run; **real access is otherwise under-reported** |
| `LOOKUP_FAILED` | the directory lookup itself failed — throttling, a missing `identitystore:GetUserId` permission, a wrong `identityStoreId` | **the grant counts in this report are unreliable**; fix the cause and re-run |

Causes are ordered worst-first, so `LOOKUP_FAILED` appears above `NO_MAPPING`, which appears above
`NOT_FOUND`. Only the Identity Center mapper distinguishes all three; the static and passthrough
mappers report `NO_MAPPING`, which is correct for them since they only consult local configuration.

Note that `NO_MAPPING` and `LOOKUP_FAILED` are *not* "fix your Ranger policies" findings — they mean
the assessment itself is incomplete, and the convertibility percentages should not be trusted until
they are resolved.

The rollup also appears in the `--output-gaps-path` file under `unresolvedPrincipals`.

### Convertibility Classification

| Category | Meaning |
|----------|---------|
| **Fully convertible** | Policy produces at least one projected LF grant and has zero *actionable* gap entries. Informational gap types — `WILDCARD_PATTERN`, `CANNOT_VALIDATE_S3_LOCATION`, `KNOWN_UNMAPPABLE_ACTION`, `NARROWED_ACTION` — never demote a policy out of this bucket, even if it has one or more of them. `CATALOG_NOT_IN_SCOPE` and `UNRESOLVED_PRINCIPAL` **are** actionable and do demote. |
| **Partially convertible** | Policy produces at least one projected LF grant but also has one or more *actionable* gap entries (some features will be silently dropped) |
| **Not convertible** | Policy produces zero projected LF grants (entirely skipped — all access types unmapped, no principal mappings, or tag/masking policy type) |

A `NOT_CONVERTIBLE` policy always has at least one gap entry recorded against it — including
when every one of its gaps is informational — so the report never classifies a policy as
"not convertible" with nothing to point to as the reason. (The `--output-gaps-path` file's
per-policy `gaps` array specifically excludes `WILDCARD_PATTERN` and `CANNOT_VALIDATE_S3_LOCATION`,
so that array can be empty for such a policy — the console "Policies with gaps" section and the
full JSON report's `gapReport.entries` always show it.)

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
| `WILDCARD_PATTERN` | Resource pattern contains wildcards and could not be expanded (no AWS credentials). Informational — does not demote convertibility. |
| `UNSUPPORTED_SERVICE_TYPE` | No adapter registered for this Ranger service type. In `server` subcommand mode, the entire policy is skipped. In `file` subcommand mode, one gap entry is recorded per skipped service (not per policy), and the `details` field includes the count of bypassed policies. |
| `UNSUPPORTED_ACTION` | An access type **no adapter recognises at all** — a coverage gap in this tool (a new Ranger release, a custom access type). The action is dropped; other actions in the policy continue. This is actionable and demotes convertibility. A service adapter's deliberately-unmapped access types (Lake Formation genuinely has no equivalent) are reported as `KNOWN_UNMAPPABLE_ACTION` instead, not here. |
| `KNOWN_UNMAPPABLE_ACTION` | An access type a service adapter recognises but deliberately never maps to anything, because Lake Formation has no equivalent concept at all — e.g. Hive `index`/`lock`/`refresh`/`repladmin`/`serviceadmin`/`tempudfadmin`; Presto/Trino `grant`/`revoke`/`execute`/`impersonate`. The access type is dropped entirely — no grant results from it. Purely informational: it never demotes a policy's convertibility. Conflated per policy (one entry lists every affected access type for that policy) and, in the JSON report, collapsed further into one entry per `(serviceType, accessType)` — see [JSON Report Format](#json-report-format) and the `knownUnmappableActions` rollup. Does **not** cover access types that were narrowed rather than dropped — see `NARROWED_ACTION` below. |
| `NARROWED_ACTION` | An access type that Lake Formation **does** have an equivalent for, but only a weaker one at the resource level in play — currently, column-scoped `all` is narrowed to `SELECT` because Lake Formation rejects `ALL` on a `TABLE_WITH_COLUMNS` resource. The grant still lands; it is just narrower than the Ranger policy requested. Purely informational: it never demotes a policy's convertibility. A policy affected only by narrowing is fully convertible and will not appear in the gaps file's per-policy `policies` array — the `narrowedActions` rollup (which carries `narrowedTo` and `reason`) is the only trace of it. Conflated per policy and collapsed per `(serviceType, accessType)` the same way as `KNOWN_UNMAPPABLE_ACTION`. |
| `UNMAPPED_RESOURCE` | Resource ID cannot be mapped to an LF resource. The resource is skipped. |
| `CATALOG_NOT_IN_SCOPE` | A Presto/Trino policy names a catalog that is not the Glue Data Catalog this tool writes to, so it produced no grants. Note `catalog=*`, `awsdatacatalog`, the AWS account id and the configured `catalogId` all resolve to the account's root catalog and are **in scope** — see [Which catalog values are in scope](#which-catalog-values-are-in-scope). Actionable: the policy is reported `NOT_CONVERTIBLE` with the catalogs it named. Before this existed such policies were skipped silently at DEBUG, which made them look inexplicably unconvertible. |
| `UNRESOLVED_PRINCIPAL` | One or more principals named by the policy could not be mapped to an AWS identity, so no grants were produced for them. Actionable and therefore demoting: a policy where *some* principals resolve is `PARTIALLY_CONVERTIBLE`; one where *none* resolve is `NOT_CONVERTIBLE`. This matches the sync server, which also grants nothing for an unresolved principal and emits an `UnmappedPrincipal` metric. Conflated per policy, and rolled up across the report by principal — see [Unmappable principals](#unmappable-principals). |
| `SCHEMA_VALIDATION_FAILURE` | A Cedar statement failed schema validation and was excluded. Valid statements in the same policy are retained. |
| `UNREGISTERED_S3_LOCATION` | An S3 prefix referenced by the policy is not covered by a registered Lake Formation data location. Reported by the assessment tool when S3 Access Grants validation is enabled. |
| `CANNOT_VALIDATE_S3_LOCATION` | S3 location validation was requested but no S3 Access Grants configuration is present. Reported by the assessment tool. Informational — does not demote convertibility. |
| `EXCLUDES_PATTERN` | Policy resource uses `isExcludes=true` ("all except this list"). Lake Formation has no exclusion-pattern resource model; the policy is skipped. |

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
