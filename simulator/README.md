# Ranger LakeFormation Simulator

A long-running correctness validation tool for the Ranger → Lake Formation sync pipeline. It continuously mutates Ranger policies at random, waits for the sync service to apply them to Lake Formation, and then independently validates that LF permissions exactly match what the Ranger policies say they should be.

The simulator catches sync bugs that unit and integration tests miss — things like incorrect diff computation when two policies overlap the same resource, or a missing revoke when a policy is disabled rather than deleted.

---

## Table of Contents

- [How It Works](#how-it-works)
- [Cross-Service Deny Semantics](#cross-service-deny-semantics)
- [Generators](#generators)
- [AWS Account Setup (Fresh Account)](#aws-account-setup-fresh-account)
- [Configuration](#configuration)
- [Running the Simulator](#running-the-simulator)
- [Reading the Output](#reading-the-output)
- [Reproduction Bundles](#reproduction-bundles)
- [Test Cases Covered](#test-cases-covered)
- [Known Coverage Gaps](#known-coverage-gaps)

---

## How It Works

Each simulator cycle:

1. Generates a random batch of 1–5 policy mutations (CREATE, UPDATE, DISABLE, ENABLE, DELETE) targeting databases and tables discovered from the Glue catalog at startup (or overridden via `databases` in config)
2. Applies the mutations to Ranger Admin via REST API
3. Polls the sync service `GET /status` endpoint until a new sync cycle completes
4. **Phase 1 — Drift check**: compares the current LF `ListPermissions` snapshot against the previous cycle's snapshot to detect unexpected changes between cycles
5. **Phase 2 — Correctness check**: independently recomputes the expected LF permissions from the current Ranger policies using a zero-dependency reimplementation of the Ranger→LF mapping, then diffs against actual LF state
6. If all permissions match: logs `Cycle N complete — all permissions correct` and proceeds
7. If a mismatch is found: writes a [reproduction bundle](#reproduction-bundles) to disk and waits one more sync cycle (remediation window)
   - If it heals: logs `TRANSIENT_VIOLATION` — a timing issue, not a bug
   - If it persists: logs `PERSISTENT_VIOLATION` — a real sync bug

```
WorkloadOrchestrator                       Validator
      │                                       │
      │  1–5 mutations/cycle                  │
      ▼                                       │
RangerPolicyClient ──── Ranger Admin ◄────────┤
                              │               │
                    policy refresh            │
                              ▼               │
                         SyncService          │
                              │               │
                    grant/revoke/list         │
                              ▼               │
                       LakeFormation ─────────┤
                                              │
                         ExpectedPermissions ─┤
                         Computer (independent)
                                              ▼
                                      ValidationResult
                                   PASS / TRANSIENT / PERSISTENT
```

---

## Cross-Service Deny Semantics

The sync service merges policies from **all configured Ranger services** into a single Cedar evaluation namespace before converting to Lake Formation permissions. Cedar's `forbid`-wins semantics apply **across service boundaries**:

> A `forbid` for principal P from any service suppresses a `permit` for the same principal P from any other service for the same `(action, resource)` pair.

**Example:** Hive grants `analyst` SELECT on `analytics.events`. Trino denies `analyst` SELECT on `analytics.events`. Effective Lake Formation permission: **no grant** — the Trino deny wins even though the grant came from Hive.

**Customer implication:** An explicit deny anywhere in any configured Ranger service will suppress access granted by any other service. Scope deny policies carefully to avoid unintended suppression across query engines.

**Simulator behavior:** The `TrinoServiceGenerator` emits deny items in ~20% of generated Trino policies to continuously exercise this code path and ensure the validator catches regressions.

---

## Generators

The simulator generates policies from 18 generators with weighted random selection (weights sum to 115). Percentages are approximate.

| Key | Generator | Weight | Config field (default) | Notes |
|-----|-----------|--------|------------------------|-------|
| `hive` | `HivePolicyGenerator` — table | 25 (~22%) | `rangerServiceName` (`lakeformation`) | Single-user table-level allow policies |
| `hive-multi` | `HivePolicyGenerator` — multi-user | 10 (~9%) | `rangerServiceName` (`lakeformation`) | 2–3 users in one policy item |
| `trino` | `TrinoServiceGenerator` | 16 (~14%) | `trinoServiceName` (`trino`) | Uses `catalog`/`schema`/`table`; ~20% include deny items |
| `datalocation` | `DataLocationPolicyGenerator` | 12 (~10%) | `rangerServiceName` (`lakeformation`) | S3 prefix `data_location_access` policies |
| `tag` | `TagPolicyGenerator` | 8 (~7%) | `tagServiceName` (`cl_tag`) | Tag-based policies (recorded as coverage gaps, not validated) |
| `emrfs` | `EmrfsPolicyGenerator` | 5 (~4%) | `emrfsServiceName` (`emrfs`) | S3 Access Grants policies |
| `hive-db` | `HivePolicyGenerator` — database | 5 (~4%) | `rangerServiceName` (`lakeformation`) | Database-level `CREATE_TABLE`/`DROP` policies |
| `hive-col` | `HivePolicyGenerator` — column | 5 (~4%) | `rangerServiceName` (`lakeformation`) | Column-scoped `TABLE_WITH_COLUMNS SELECT` policies |
| `hive-unmapped` | `HivePolicyGenerator` — unmapped principal | 2 (~2%) | `rangerServiceName` (`lakeformation`) | Uses `ghost_user` (absent from `principalMappings`); validates zero-grant safety |
| `hive-grantable` | `HivePolicyGenerator` — grantable | 3 (~3%) | `rangerServiceName` (`lakeformation`) | `delegateAdmin=true` table-level policies; validates `WITH GRANT OPTION` |
| `hive-wildcard` | `HivePolicyGenerator` — wildcard | 3 (~3%) | `rangerServiceName` (`lakeformation`) | `table="*"` wildcard; validated via static table map expansion |
| `hive-deny` | `HivePolicyGenerator` — deny | 4 (~3%) | `rangerServiceName` (`lakeformation`) | Deny-only policies (`denyPolicyItems`); net LF grants must be zero |
| `hive-group` | `HivePolicyGenerator` — group | 1 (~1%) | `rangerServiceName` (`lakeformation`) | Group principal (must resolve via `principalMappings`) |
| `hive-role` | `HivePolicyGenerator` — role | 1 (~1%) | `rangerServiceName` (`lakeformation`) | Role principal (must resolve via `principalMappings`) |
| `emrspark` | `EmrSparkPolicyGenerator` — table | 8 (~7%) | `emrSparkServiceName` (`amazon-emr-spark`) | EMR Spark table-level policies; validated only when `validateEmrSpark=true` |
| `emrspark-db` | `EmrSparkPolicyGenerator` — database | 3 (~3%) | `emrSparkServiceName` (`amazon-emr-spark`) | EMR Spark database-level policies |
| `emrspark-col` | `EmrSparkPolicyGenerator` — column | 2 (~2%) | `emrSparkServiceName` (`amazon-emr-spark`) | EMR Spark column-level (`TABLE_WITH_COLUMNS`) policies |
| `emrspark-deny` | `EmrSparkPolicyGenerator` — deny | 2 (~2%) | `emrSparkServiceName` (`amazon-emr-spark`) | EMR Spark deny-only policies |

> **Note:** The `hive-all` generator (`generateAllAccessTablePolicy`) exists in `HivePolicyGenerator` but is **not wired** because the `lakeformation` Ranger service definition rejects `"all"` as an access type. It is preserved for future use against a `hive`-type service.

> **EMR Spark validation:** EMR Spark generators always emit policies to Ranger, but `Phase2CorrectnessValidator` only checks LF grants for EMR Spark when `validateEmrSpark: true` in the simulator config. This requires the sync service's `rangerServices` list to include `amazon-emr-spark`. By default (`validateEmrSpark: false`), EMR Spark policies are generated and applied to Ranger but excluded from correctness checks.

---

## AWS Account Setup (Fresh Account)

Run these commands once before using the simulator for the first time. Replace `ACCOUNT_ID`, `REGION`, and `SYNC_ROLE_ARN` with your values throughout. The commands assume you have an IAM user or role with `iam:CreateRole`, `iam:AttachRolePolicy`, `lakeformation:*`, and `glue:*` permissions.

### 1. Set shell variables

```bash
export AWS_PROFILE=your-profile
export REGION=us-west-2
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account: $ACCOUNT_ID, Region: $REGION"
```

### 2. Create the sync service IAM role

This role is assumed by the sync service to call LakeFormation and Glue APIs.

```bash
# Create the role (replace the trust policy principal with your EC2/ECS/local role)
aws iam create-role \
  --role-name RangerLFSyncRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"AWS": "arn:aws:iam::'"$ACCOUNT_ID"':root"},
      "Action": "sts:AssumeRole"
    }]
  }' \
  --description "Sync service role for Ranger LF sync"

# Attach Lake Formation full access
aws iam attach-role-policy \
  --role-name RangerLFSyncRole \
  --policy-arn arn:aws:iam::aws:policy/AWSLakeFormationDataAdmin

# Attach Glue read access (for catalog resolution and wildcard expansion)
aws iam attach-role-policy \
  --role-name RangerLFSyncRole \
  --policy-arn arn:aws:iam::aws:policy/AWSGlueConsoleFullAccess
```

### 3. Create IAM roles for the four test principals

The simulator generates policies for Ranger users `analyst`, `etl_user`, `data_admin`, and `viewer`. Each needs a corresponding IAM role so LakeFormation can grant permissions to it.

```bash
for PRINCIPAL in analyst etl_user data_admin viewer; do
  aws iam create-role \
    --role-name "ranger-sim-${PRINCIPAL}" \
    --assume-role-policy-document '{
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Principal": {"AWS": "arn:aws:iam::'"$ACCOUNT_ID"':root"},
        "Action": "sts:AssumeRole"
      }]
    }' \
    --description "Simulator test principal: ${PRINCIPAL}"
  echo "Created role: ranger-sim-${PRINCIPAL}"
done
```

### 4. Configure Lake Formation in managed mode

Lake Formation must be switched out of legacy IAM mode before the sync service can manage permissions. This is a **one-time account-level change** — it removes the default IAM pass-through and puts LakeFormation in charge.

> **Warning:** This affects all LF resources in the account. If other teams use this account, coordinate before running this command.

```bash
# Add the sync role and your admin role as LF data lake admins
ADMIN_ROLE_ARN=$(aws iam get-role --role-name Admin --query 'Role.Arn' --output text 2>/dev/null \
  || aws sts get-caller-identity --query 'Arn' --output text)

aws lakeformation put-data-lake-settings \
  --region "$REGION" \
  --data-lake-settings "{
    \"DataLakeAdmins\": [
      {\"DataLakePrincipalIdentifier\": \"arn:aws:iam::${ACCOUNT_ID}:role/RangerLFSyncRole\"},
      {\"DataLakePrincipalIdentifier\": \"${ADMIN_ROLE_ARN}\"}
    ],
    \"CreateDatabaseDefaultPermissions\": [],
    \"CreateTableDefaultPermissions\": []
  }"

# Verify
aws lakeformation get-data-lake-settings --region "$REGION" \
  --query 'DataLakeSettings.{Admins:DataLakeAdmins,DBDefaults:CreateDatabaseDefaultPermissions,TblDefaults:CreateTableDefaultPermissions}'
```

The `CreateDatabaseDefaultPermissions` and `CreateTableDefaultPermissions` arrays must be empty (no `IAM_ALLOWED_PRINCIPALS`). If they still contain entries, the sync service's grants will be shadowed by the IAM pass-through and validation will fail.

### 5. Create the Glue databases

The simulator generates policies for three databases. Create them in Glue:

```bash
for DB in analytics staging default_sim; do
  aws glue create-database \
    --region "$REGION" \
    --database-input "{\"Name\": \"${DB}\"}" \
    2>/dev/null && echo "Created database: $DB" \
    || echo "Database $DB already exists"
done
```

### 6. Create the Glue tables

Five tables per database, each with the full 8-column schema used by `HivePolicyGenerator.generateColumnPolicy()`. LakeFormation rejects `TABLE_WITH_COLUMNS` grants for columns that do not exist in the table schema, so all 8 columns must be present.

```bash
COLUMNS='[
  {"Name":"id","Type":"string"},
  {"Name":"name","Type":"string"},
  {"Name":"value","Type":"string"},
  {"Name":"created_at","Type":"string"},
  {"Name":"status","Type":"string"},
  {"Name":"amount","Type":"string"},
  {"Name":"category","Type":"string"},
  {"Name":"region","Type":"string"}
]'

for DB in analytics staging default_sim; do
  for TABLE in events users orders products sessions; do
    aws glue create-table \
      --region "$REGION" \
      --database-name "$DB" \
      --table-input "{
        \"Name\": \"${TABLE}\",
        \"StorageDescriptor\": {
          \"Columns\": ${COLUMNS},
          \"Location\": \"s3://your-bucket/${DB}/${TABLE}/\",
          \"InputFormat\": \"org.apache.hadoop.mapred.TextInputFormat\",
          \"OutputFormat\": \"org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat\",
          \"SerdeInfo\": {\"SerializationLibrary\": \"org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe\"}
        }
      }" \
      2>/dev/null && echo "Created table: $DB.$TABLE" \
      || echo "Table $DB.$TABLE already exists"
  done
done
```

> Replace `s3://your-bucket/` with any S3 prefix — the simulator does not read from S3, so the location just needs to be syntactically valid.

Alternatively, run `simulator/scripts/setup-glue-tables.sh --region $REGION` which creates or updates all 15 tables idempotently.

### 7. Grant the sync role permissions on the Glue catalog

The sync role needs LF permissions to grant/revoke on behalf of the sync service:

```bash
# Grant on each database
for DB in analytics staging default_sim; do
  aws lakeformation grant-permissions \
    --region "$REGION" \
    --principal "DataLakePrincipalIdentifier=arn:aws:iam::${ACCOUNT_ID}:role/RangerLFSyncRole" \
    --resource "{\"Database\": {\"Name\": \"${DB}\"}}" \
    --permissions ALL \
    --permissions-with-grant-option ALL

  # Grant on all tables in the database
  aws lakeformation grant-permissions \
    --region "$REGION" \
    --principal "DataLakePrincipalIdentifier=arn:aws:iam::${ACCOUNT_ID}:role/RangerLFSyncRole" \
    --resource "{\"Table\": {\"DatabaseName\": \"${DB}\", \"TableWildcard\": {}}}" \
    --permissions ALL \
    --permissions-with-grant-option ALL

  echo "Granted sync role permissions on $DB"
done
```

### 8. Verify the setup

```bash
# Should show 0 permissions for test principals (nothing granted yet)
aws lakeformation list-permissions \
  --region "$REGION" \
  --query 'PrincipalResourcePermissions[?contains(Principal.DataLakePrincipalIdentifier, `ranger-sim`)]' \
  --output table

# Should show RangerLFSyncRole as a data lake admin
aws lakeformation get-data-lake-settings \
  --region "$REGION" \
  --query 'DataLakeSettings.DataLakeAdmins[*].DataLakePrincipalIdentifier' \
  --output table
```

---

## Configuration

### Sync service config (`conf/server-config-simulator-test.yaml`)

```yaml
rangerConfig:
  rangerAdminUrl: "http://localhost:6080"
  username: "admin"
  password: "rangerR0cks!"

awsConfig:
  region: "us-west-2"              # must match where Glue databases were created
  catalogId: "427250191192"        # your AWS account ID
  roleArn: "arn:aws:iam::427250191192:role/RangerLFSyncRole"

principalMapping:
  userMappings:
    "analyst":    "arn:aws:iam::427250191192:role/ranger-sim-analyst"
    "etl_user":   "arn:aws:iam::427250191192:role/ranger-sim-etl_user"
    "data_admin": "arn:aws:iam::427250191192:role/ranger-sim-data_admin"
    "viewer":     "arn:aws:iam::427250191192:role/ranger-sim-viewer"

policyRefreshIntervalMs: 15000     # how often the sync service polls Ranger (ms)
wildcardRefreshIntervalSeconds: 0  # disable wildcard refresh for now

deadLetterLogPath: "/tmp/ranger-sim/dead-letter.jsonl"
checkpointPath: "/tmp/ranger-sim/sync-checkpoint.json"

server:
  shutdownTimeoutSeconds: 30
  logLevel: INFO
  statusPort: 18080                # simulator polls this port

reverseSync:
  enabled: false
tagSync:
  enabled: false
```

### Simulator config (`conf/simulator-config.json`)

```json
{
  "cycleIntervalSeconds": 30,
  "awsRegion": "us-west-2",
  "awsAccountId": "427250191192",
  "roleArn": "arn:aws:iam::427250191192:role/RangerLFSyncRole",
  "rangerAdminUrl": "http://localhost:6080",
  "rangerAdminUser": "admin",
  "rangerAdminPassword": "rangerR0cks!",
  "rangerServiceName": "lakeformation",
  "trinoServiceName": "trino",
  "emrfsServiceName": "emrfs",
  "emrSparkServiceName": "amazon-emr-spark",
  "tagServiceName": "cl_tag",
  "principalPool": ["analyst", "etl_user", "data_admin", "viewer"],
  "principalMappings": {
    "analyst":    "arn:aws:iam::427250191192:role/ranger-sim-analyst",
    "etl_user":   "arn:aws:iam::427250191192:role/ranger-sim-etl_user",
    "data_admin": "arn:aws:iam::427250191192:role/ranger-sim-data_admin",
    "viewer":     "arn:aws:iam::427250191192:role/ranger-sim-viewer"
  },
  "cycleWaitTimeoutSeconds": 120,
  "statusHost": "localhost",
  "statusPort": 18080,
  "reproductionBundleDir": "/tmp/ranger-sim/bundles",
  "validateEmrSpark": false
}
```

### Config field reference

| Field | Description | Default |
|-------|-------------|---------|
| `cycleIntervalSeconds` | Pause between simulator cycles | `60` |
| `awsRegion` | AWS region for LF and Glue API calls | `"us-east-1"` |
| `awsAccountId` | AWS account ID | `"unknown"` |
| `rangerAdminUrl` | Ranger Admin base URL | required |
| `rangerAdminUser` | Ranger Admin username | required |
| `rangerAdminPassword` | Ranger Admin password | required |
| `rangerServiceName` | Ranger service instance name for Hive/LF generators | `"lakeformation"` |
| `trinoServiceName` | Ranger service instance name for Trino generators | `"trino"` |
| `emrfsServiceName` | Ranger service instance name for EMRFS generators | `"emrfs"` |
| `emrSparkServiceName` | Ranger service instance name for EMR Spark generators | `"amazon-emr-spark"` |
| `tagServiceName` | Ranger service instance name for tag generators | `"cl_tag"` |
| `principalPool` | Ranger usernames to use in generated policies; if empty, falls back to `principalMappings` keys | `[]` |
| `principalMappings` | Ranger username → IAM role ARN; used to filter LF actual permissions and as the oracle for expected permissions | `{}` |
| `cycleWaitTimeoutSeconds` | Max seconds to wait for a sync cycle after mutations | `300` |
| `statusHost` | Hostname of the sync service status endpoint | `"localhost"` |
| `statusPort` | Port of the sync service `GET /status` endpoint | `18080` |
| `reproductionBundleDir` | Directory for violation bundles and mutation log | `"reproduction-bundles"` |
| `databases` | Optional map of `db → [table, ...]`. When present, used directly instead of querying the Glue catalog. Useful for offline testing or restricting the simulator to a subset of tables. | `null` (Glue discovery) |
| `s3Prefixes` | S3 prefix list used by `DataLocationPolicyGenerator` and `EmrfsPolicyGenerator` | `["s3://my-bucket/data/", "s3://my-bucket/logs/"]` |
| `roleArn` | IAM role ARN to assume for all AWS API calls (LF, Glue, S3 Control). If absent, uses the default credential chain. | `null` |
| `validateEmrSpark` | When `true`, includes EMR Spark LF grants in Phase2 correctness checks. Requires the sync service to have `amazon-emr-spark` in its `rangerServices` list. | `false` |

---

## Running the Simulator

### Step 1 — Build

```bash
# From repository root
mvn clean package -DskipTests

cd simulator
mvn package -DskipTests
```

### Step 2 — Start the Ranger Docker stack

```bash
# From repository root
mvn package -Pstart-stack -DskipTests
```

Wait ~90 seconds for all containers to become healthy, then verify:

```bash
docker compose -f integration-test/docker/docker-compose.yml ps

curl -s -u admin:rangerR0cks! http://localhost:6080/service/public/v2/api/service \
  | python3 -c "import sys,json; print([s['name'] for s in json.load(sys.stdin)])"
# → ['lakeformation', 'cl_tag']
```

### Step 2.5 — Provision simulator Ranger services and Glue tables

The simulator requires `trino`, `emrfs`, and `amazon-emr-spark` Ranger service instances in addition to the `lakeformation` and `cl_tag` instances provisioned by the integration-test stack. Run both setup scripts once after the stack is healthy:

```bash
# Provision the Ranger service instances (requires Ranger Admin access)
simulator/scripts/setup-ranger-services.sh --ranger-url http://localhost:6080

# Create or update all 15 Glue tables with the full 8-column schema
# (requires AWS credentials with glue:CreateTable, glue:UpdateTable, glue:GetTable)
simulator/scripts/setup-glue-tables.sh --region $REGION
```

Both scripts are idempotent — re-running them on an already-provisioned environment is safe. After `setup-ranger-services.sh` completes, verify all five Ranger services are registered:

```bash
curl -s -u admin:rangerR0cks! http://localhost:6080/service/public/v2/api/service \
  | python3 -c "import sys,json; print([s['name'] for s in json.load(sys.stdin)])"
# → ['lakeformation', 'cl_tag', 'trino', 'emrfs', 'amazon-emr-spark']
```

> **Note:** `setup-ranger-services.sh` currently provisions `trino` and `emrfs` only. The `amazon-emr-spark` service instance is provisioned by the integration-test stack's `setup-ranger-services.sh` script. If it is missing, create it manually:
> ```bash
> curl -s -u admin:rangerR0cks! -X POST http://localhost:6080/service/public/v2/api/service \
>   -H "Content-Type: application/json" \
>   -d '{"name":"amazon-emr-spark","type":"amazon-emr-spark","configs":{}}'
> ```

Alternatively, use the Maven `run-simulator` profile (Step 4 below) which runs `setup-ranger-services.sh` automatically before launching the simulator jar. You still need to run `setup-glue-tables.sh` manually if the Glue tables have not been created yet.

### Step 3 — Start the sync service

```bash
# From repository root
AWS_PROFILE=your-profile \
  java -jar target/ranger-lakeformation-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  conf/server-config-simulator-test.yaml \
  > /tmp/sync-service.log 2>&1 &

echo "Sync service PID=$!"
```

Wait for the first cycle:

```bash
# Poll until lastCompletedCycle > 0
until curl -s http://localhost:18080/status | grep -q '"lastCompletedCycle":[1-9]'; do
  echo "Waiting for sync service..."; sleep 3
done
curl -s http://localhost:18080/status
# → {"lastCompletedCycle":1,"lastCompletedWildcardRefreshCycle":0,"state":"running"}
```

### Step 4 — Start the simulator

**Option A — direct jar (recommended for long runs):**

```bash
# From repository root
AWS_PROFILE=your-profile \
  java -jar simulator/target/ranger-lakeformation-simulator-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  conf/simulator-config.json \
  > /tmp/simulator.log 2>&1 &

echo "Simulator PID=$!"
```

**Option B — Maven profile (runs setup script automatically):**

```bash
# From repository root — provisions Ranger services then launches the simulator
AWS_PROFILE=your-profile \
  mvn -pl simulator -Prun-simulator integration-test \
  -DRANGER_URL=http://localhost:6080 \
  -DAWS_REGION=us-west-2
```

On startup the simulator queries the Glue catalog to build its list of databases and tables. You should see lines like:

```
Glue catalog discovery: found 3 databases
  analytics: 5 tables
  staging: 5 tables
  default_sim: 5 tables
```

If discovery returns zero databases, check that `awsRegion` in the config matches where you created the Glue databases and that your AWS credentials have `glue:GetDatabases` / `glue:GetTables` permissions. You can also bypass discovery by adding a `databases` key to the config (see [Config field reference](#configuration)).

### Step 5 — Monitor

```bash
# Key events only
tail -f /tmp/simulator.log | grep -E \
  "=== Simulator|Applying batch|Ranger ID|VIOLATION|ALERT|all permissions correct|Phase2 mismatch"

# Or watch both logs side by side
tail -f /tmp/sync-service.log &
tail -f /tmp/simulator.log
```

### Clean restart (wiping all state)

To start from a known-clean state — no Ranger policies, no LF permissions, no checkpoint:

```bash
# 1. Stop running processes
pkill -f "ranger-lakeformation-simulator"
pkill -f "ranger-lakeformation-plugin"

# 2. Delete all simulator Ranger policies
for SVC in lakeformation cl_tag trino emrfs amazon-emr-spark; do
  curl -s -u admin:rangerR0cks! \
    "http://localhost:6080/service/public/v2/api/policy?serviceName=${SVC}&pageSize=200" \
    | python3 -c "
import sys, json
d = json.load(sys.stdin)
items = d if isinstance(d, list) else d.get('vXPolicies', [])
for p in items: print(p['id'])
" | while read -r ID; do
    curl -s -o /dev/null -w "DELETE $SVC/$ID → %{http_code}\n" -X DELETE \
      -u admin:rangerR0cks! \
      "http://localhost:6080/service/public/v2/api/policy/${ID}"
  done
done

# 3. Revoke all LF permissions for ranger-sim-* principals
aws lakeformation list-permissions --region "$REGION" --output json \
  | python3 -c "
import sys, json, subprocess
data = json.load(sys.stdin)
perms = [p for p in data.get('PrincipalResourcePermissions', [])
         if 'ranger-sim' in p.get('Principal',{}).get('DataLakePrincipalIdentifier','')]
for p in perms:
    subprocess.run(['aws', 'lakeformation', 'revoke-permissions',
                    '--region', '$REGION',
                    '--principal', json.dumps(p['Principal']),
                    '--resource', json.dumps(p['Resource']),
                    '--permissions', *p['Permissions']])
    print('Revoked', p['Principal']['DataLakePrincipalIdentifier'].split('/')[-1])
"

# 4. Clear local checkpoint and bundles
rm -f /tmp/ranger-sim/sync-checkpoint.json /tmp/ranger-sim/dead-letter.jsonl
rm -rf /tmp/ranger-sim/bundles && mkdir -p /tmp/ranger-sim/bundles
```

Then proceed from Step 3 to restart the sync service and simulator.

### Step 6 — Stop everything

```bash
pkill -f "ranger-lakeformation-simulator"
pkill -f "ranger-lakeformation-plugin"
mvn validate -Pstop-stack        # stops Docker
```

---

## Reading the Output

### Clean cycle

```
=== Simulator cycle 3 ===
Applying batch of 2 mutations
Created policy sim-policy-334104226477583 → Ranger ID 8
Cycle 3 complete — all permissions correct
```

### Transient violation (self-healed)

```
Phase1 drift detected after cycle 12
Violation bundle written for cycle 12
Waiting for remediation cycle after cycle 12
Remediation cycle 13 completed
SIMULATOR ALERT [TRANSIENT_VIOLATION]: Self-healed after remediation cycle 13 — bundle: 2026-05-27T12:00:00Z
```

A transient violation means LF was temporarily inconsistent but corrected itself in the next sync cycle. This is usually a timing issue, not a bug. The reproduction bundle is still written so you can review what happened.

### Persistent violation (real bug)

```
Phase1 drift detected after cycle 14
Violation bundle written for cycle 14
Waiting for remediation cycle after cycle 14
Remediation cycle 15 completed
SIMULATOR ALERT [PERSISTENT_VIOLATION]: Persistent violation after remediation cycle 15 — bundle: 2026-05-27T12:05:00Z
PERSISTENT VIOLATION detected after remediation cycle 15
```

A persistent violation means the sync service failed to produce the correct LF state even after a full additional sync cycle. This is a real bug. The reproduction bundle contains everything needed to reproduce it.

---

## Reproduction Bundles

Every violation writes a timestamped directory under `reproductionBundleDir`:

```
/tmp/ranger-sim/bundles/
  mutation.log                            ← append log of all mutations for this run
  violation_2026-05-27_12-05-00/
    mutations.json                        ← full mutation log up to this violation
    ranger-snapshot.json                  ← all Ranger policies at time of violation
    lf-actual.json                        ← full ListPermissions output
    lf-expected.json                      ← expected permissions (independent computation)
    diff.json                             ← structured diff: over-grants and under-grants
    cycle-sequence.json                   ← { violationDetectedAfterCycle, lastSuccessfulCycle }
    README.txt                            ← step-by-step reproduction instructions
```

`diff.json` is the most useful file:

```json
{
  "overGrants": [
    {
      "principalArn": "arn:aws:iam::427250191192:role/ranger-sim-analyst",
      "resourceType": "TABLE_WITH_COLUMNS",
      "resourceId": "analytics.events",
      "permission": "SELECT",
      "grantable": false
    }
  ],
  "underGrants": [],
  "description": "Phase2 mismatch: 1 over-grants (in LF but not expected), 0 under-grants (expected but not in LF)"
}
```

- **`overGrants`**: permissions that exist in LF but should not (a missed revoke — security issue)
- **`underGrants`**: permissions that should exist in LF but do not (a missed grant — access denied when it should be allowed)

---

## Test Cases Covered

### Operations generated per cycle

Each cycle generates 1–5 mutations. Each mutation is drawn independently with these weights:

| Operation | Weight | What happens |
|-----------|--------|--------------|
| **CREATE** | 30% | New Hive policy sent to Ranger. Sync service should grant LF permissions. |
| **UPDATE** | 20% | Existing policy replaced with a new payload (different db/table/user/accesses). Sync service should revoke old grants and apply new ones. |
| **DISABLE** | 15% | Policy `isEnabled` set to `false`. Sync service should revoke all LF grants for that policy. |
| **ENABLE** | 15% | Policy `isEnabled` set back to `true`. Sync service should restore LF grants. |
| **DELETE** | 10% | Policy deleted from Ranger. Sync service should revoke all LF grants. |
| *No-op* | 10% | Slot drawn but nothing sent. Keeps batch size variable. |

### Generated policy payload

Each CREATE/UPDATE generates a single-item table-level allow policy. The database and table come from the Glue-discovered resource map, so every generated policy targets a table that actually exists:

```json
{
  "service": "lakeformation",
  "isEnabled": true,
  "policyType": 0,
  "resources": {
    "database": {"values": ["<any discovered database>"]},
    "table":    {"values": ["<a table that belongs to that database>"]}
  },
  "policyItems": [{
    "users":      ["<analyst | etl_user | data_admin | viewer>"],
    "accesses":   [/* 1–3 random picks from: select, insert, delete, describe */],
    "delegateAdmin": false
  }],
  "denyPolicyItems": []
}
```

With the default 3-database, 5-tables-each setup:

- **3 databases** × **5 tables** = 15 resource combinations (always db-consistent: no cross-db table picks)
- **4 principals**
- **14 possible access subsets** (non-empty subsets of `{select, insert, delete, describe}` of size 1–3)
- **5 operation types** across the policy lifecycle

---

## Known Coverage Gaps

The following scenarios are not currently exercised by the simulator. Each represents a real code path in the sync service that could harbor bugs.

### 1. GDC table or database deletion with live Ranger policies

**Scenario:** A Ranger policy grants `analyst` SELECT on `analytics.events`. The Glue table `events` is then deleted from the Data Catalog. The sync service should detect that the resource no longer exists and revoke the dangling LF grant.

**Risk:** A dangling LF grant on a non-existent resource is a security over-permission. The sync service likely does not handle this case.

---

### 2. Wildcard table policies ✅

**Scenario:** A Ranger policy uses `"table": {"values": ["*"]}` or a glob like `"events_*"`. The sync service must expand this against the Glue catalog. When a new matching table is later added to Glue, the `WildcardRefreshScheduler` should pick it up and apply the grant automatically.

**Covered by:** `HivePolicyGenerator.generateWildcardTablePolicy()` wired as the `hive-wildcard` generator entry (3% of mutations). Always emits `"*"` as the table value; the `ExpectedPermissionsComputer` expands it against the configured `databaseTables` map.

---

### 3. Database-level policies (no table resource) ✅

**Scenario:** A Ranger policy has only a `database` resource (no `table`). These map to `CREATE_TABLE` / `DROP` LF database permissions rather than table-level grants.

**Covered by:** `HivePolicyGenerator.generateDatabasePolicy()` wired as the `hive-db` generator entry (5% of mutations).

---

### 4. Column-level policies ✅

**Scenario:** A Ranger policy includes a `column` resource. In LF, this maps to `TABLE_WITH_COLUMNS` with specific column names, and `DESCRIBE` must be stripped from the grant.

**Covered by:** `HivePolicyGenerator.generateColumnPolicy()` wired as the `hive-col` generator entry (5% of mutations).

---

### 5. Deny policies ✅

**Scenario:** A Ranger policy has entries in `denyPolicyItems`. The sync service converts these to Cedar `forbid` statements which should suppress permits for the same (principal, action, resource) triple. The net result should be zero LF grants for the denied combination.

**Covered by:** `HivePolicyGenerator.generateDenyTablePolicy()` wired as the `hive-deny` generator entry (4% of mutations). Emits policies with populated `denyPolicyItems` and empty `policyItems`. Cross-service deny (a Trino deny suppressing a Hive grant) is also continuously exercised by `TrinoServiceGenerator` (~20% of Trino policies contain deny items).

---

### 6. Cross-policy permit + deny suppression ✅

**Scenario:** Policy A grants `analyst` SELECT on `analytics.events`. Policy B denies `analyst` SELECT on `analytics.events`. The sync service must see both, combine them via Cedar, and produce zero LF grants — even though policy A alone would produce a grant.

**Covered by:** With a small resource pool (3 databases, ~5 tables per database), `hive` (grant) and `hive-deny` generators will naturally produce overlapping (principal, resource) combinations across cycles. The Phase 2 validator enforces that the combined Cedar evaluation produces correct net grants.

---

### 7. Overlapping policies for the same resource ✅

**Scenario:** Policy A and Policy B both independently grant `analyst` SELECT on `analytics.events`. When policy A is deleted, the sync service must keep the LF grant because policy B still covers it. If it revokes it, `analyst` loses access they should still have.

**Covered by:** With a small resource pool and independent `hive` generators running across cycles, overlapping grants on the same resource occur naturally. The Phase 2 validator detects false under-grants: if the LF permission is missing but still expected from surviving policies, it flags the violation.

---

### 8. Grantable permissions (`delegateAdmin=true`) ✅

**Scenario:** A Ranger policy has `delegateAdmin=true` on a policy item. This should flow through to `permissionsWithGrantOption` in the LF grant (`WITH GRANT OPTION`).

**Covered by:** `HivePolicyGenerator.generateGrantableTablePolicy()` wired as the `hive-grantable` generator entry (3% of mutations). Always emits `delegateAdmin=true`; the `ExpectedPermissionsComputer` sets `grantable=true` on the expected `SimulatorPermission` and the Phase 2 validator checks it.

---

### 9. Multi-user policies ✅

**Scenario:** A single Ranger policy item has multiple users (e.g., both `analyst` and `etl_user`). The sync service should produce separate LF grants for each user.

**Covered by:** `HivePolicyGenerator.generateMultiUserTablePolicy()` wired as the `hive-multi` generator entry (10% of mutations). Emits 2–3 users in a single `policyItems` entry.

---

### 10. Group and role principals ✅

**Scenario:** A Ranger policy grants access to a group (`"groups": ["data_team"]`) or a role (`"roles": ["etl_role"]`) rather than a user. These require a group/role → IAM ARN mapping in `principalMappings`.

**Covered by:** `HivePolicyGenerator.generateGroupTablePolicy()` and `generateRoleTablePolicy()` wired as the `hive-group` and `hive-role` generator entries (1% each). Both draw principals from the same pool as user policies; the sync service must resolve them via `principalMappings` in the same way.

---

### 11. Unmapped principal ✅

**Scenario:** A Ranger policy references a user that has no entry in `principalMappings`. The sync service should produce zero LF grants for that policy item and record a gap entry — without throwing an exception.

**Covered by:** `HivePolicyGenerator.generateUnmappedPrincipalPolicy()` wired as the `hive-unmapped` generator entry (2% of mutations). Uses the fixed principal name `ghost_user`, which is deliberately absent from `principalMappings`.

---

### 12. `all` access type expansion ✅

**Scenario:** A Ranger policy uses `"type": "all"`, which should expand to `SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE` in LF.

**Covered by:** `HivePolicyGenerator.generateAllAccessTablePolicy()` wired as the `hive-all` generator entry (3% of mutations).

---

### 13. Sync service restart mid-run

**Scenario:** The sync service is killed and restarted while the simulator has active policies in Ranger. The service should read its checkpoint file and resume without re-granting already-granted permissions or missing any pending revokes.

---

### Coverage summary

| Scenario | Covered |
|----------|---------|
| Table-level CREATE / UPDATE / DISABLE / ENABLE / DELETE | ✅ |
| select, insert, delete, describe in 1–3 combinations | ✅ |
| 4 principals, 3 databases, 5 tables | ✅ |
| Disabled policy produces no LF grant | ✅ |
| Re-enabled policy restores LF grant | ✅ |
| Deleted policy revokes LF grant | ✅ |
| GDC table/database deletion with live Ranger policy | ❌ |
| Wildcard table policies (`*`) | ✅ (`hive-wildcard`, ~3%) |
| Database-level policies | ✅ (`hive-db`, ~4%) |
| Column-level policies | ✅ (`hive-col`, ~4%) |
| Deny policies (single-service) | ✅ (`hive-deny`, ~3%) |
| Cross-policy permit + deny suppression | ✅ (emerges from overlapping `hive` + `hive-deny` on small resource pool) |
| Overlapping policies for the same resource | ✅ (emerges naturally from independent generators on small resource pool) |
| Grantable permissions (`delegateAdmin=true`) | ✅ (`hive-grantable`, ~3%) |
| Multi-user policies | ✅ (`hive-multi`, ~9%) |
| Group and role principals | ✅ (`hive-group` ~1%, `hive-role` ~1%) |
| Unmapped principal | ✅ (`hive-unmapped`, ~2%) |
| Data location (`data_location_access`) policies | ✅ (`datalocation`, ~10%) |
| Trino catalog→schema→table policies with deny | ✅ (`trino`, ~14%) |
| EMRFS / S3 Access Grants policies | ✅ (`emrfs`, ~4%; S3AG validation requires `S3AG_INSTANCE_ARN` env var) |
| EMR Spark table/db/column/deny policies | ✅ generated always; validated only when `validateEmrSpark: true` |
| `all` access type expansion | ❌ (lakeformation Ranger service rejects "all"; generator exists but not wired) |
| Sync service restart recovery | ❌ |
