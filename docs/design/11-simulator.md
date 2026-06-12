# Simulator Architecture

The simulator lives in `simulator/src/main/java/com/example/ranger/lakeformation/simulator/`. It is a fully independent process that:
1. Generates and mutates random Ranger policies
2. Waits for the sync service to process each cycle
3. Validates LF actual state against independently-computed expected state
4. Records reproduction bundles on any violation

**Critical design rule:** `ExpectedPermissionsComputer` must NOT import any class from `com.amazonaws.policyconverters`. It is an independent oracle.

---

## Driver layer

### `driver/SimulatorConfig.java`

All fields immutable after construction. Loaded from JSON file (`args[0]`).

| Field | Default | Purpose |
|---|---|---|
| `cycleIntervalSeconds` | 60 | Sleep between simulator cycles |
| `awsRegion` | `us-east-1` | Region for all AWS SDK clients |
| `rangerAdminUrl/User/Password` | required | Ranger Admin REST |
| `rangerServiceName` | `lakeformation` | Primary LF/Hive service |
| `trinoServiceName` | `trino` | |
| `emrfsServiceName` | `emrfs` | |
| `emrSparkServiceName` | `amazon-emr-spark` | |
| `tagServiceName` | `cl_tag` | Must contain `"tag"` |
| `principalPool` | empty → derived from `principalMappings` keys | Ranger user names |
| `principalMappings` | required | `rangerName → IAM ARN` for Phase2 validation |
| `awsAccountId` | `unknown` | Used as `catalogId` for LF calls |
| `cycleWaitTimeoutSeconds` | 300 | Timeout for `CycleWaiter` |
| `statusHost/Port` | `localhost:18080` | Sync service health endpoint |
| `reproductionBundleDir` | `reproduction-bundles` | Where violation bundles are written |
| `databases` | null → Glue discovery | Optional static `db → [table,...]` map |
| `s3Prefixes` | two `s3://my-bucket/...` defaults | DataLocation + EMRFS generators |
| `roleArn` | null | If set, uses STS AssumeRole for all AWS calls |
| `validateEmrSpark` | false | Include EMR Spark in Phase2 managed-service filter |

---

### `driver/SimulatorMain.java`

Entry point and wiring hub.

#### The 7-step cycle (`runOneCycle`)

| Step | Action |
|---|---|
| 1 | `SyncServiceStatusClient.fetchStatus()` → record `syncCycleBefore` |
| 2 | `WorkloadOrchestrator.generateBatch()` → `MutationDriver.applyBatch()` |
| 3 | `CycleWaiter.waitForCycleAfter(syncCycleBefore)` — block until sync service advances |
| 4 | `LFPermissionsFetcher.fetchAll()` + `S3AgPermissionsFetcher.fetchAll()` → filter to `managedArns` |
| 5 | `Phase1DriftValidator.validate(actual)` |
| 6 | `RangerPolicyClient.listPolicies()` × 5 service names → `Phase2CorrectnessValidator.validate()` |
| 7 | If violation: write `ReproductionBundle`, `RemediationRunner.waitForRemediation()`, re-validate, emit alert |

#### Generator weight table

| Entry name | Generator method | Weight |
|---|---|---|
| `hive` | `generateTablePolicy` | 25 |
| `trino` | `TrinoServiceGenerator.generate` | 16 |
| `datalocation` | `DataLocationPolicyGenerator.generate` | 12 |
| `tag` | `TagPolicyGenerator.generate` | 8 |
| `emrspark` | `generateTablePolicy` | 8 |
| `hive-multi` | `generateMultiUserTablePolicy` | 10 |
| `hive-db` | `generateDatabasePolicy` | 5 |
| `hive-col` | `generateColumnPolicy` | 5 |
| `hive-wildcard` | `generateWildcardTablePolicy` | 3 |
| `hive-grantable` | `generateGrantableTablePolicy` | 3 |
| `hive-deny` | `generateDenyTablePolicy` | 4 |
| `hive-unmapped` | `generateUnmappedPrincipalPolicy` | 2 |
| `hive-group` | `generateGroupTablePolicy` | 1 |
| `hive-role` | `generateRoleTablePolicy` | 1 |
| `emrfs` | `EmrfsPolicyGenerator.generate` | 5 |
| `emrspark-db` | `generateDatabasePolicy` | 3 |
| `emrspark-col` | `generateColumnPolicy` | 2 |
| `emrspark-deny` | `generateDenyTablePolicy` | 2 |

Total weight: 115. `hive-all` deliberately NOT wired (lakeformation service rejects `"all"` as access type).

---

### `driver/RangerPolicyClient.java`

Thin HTTP wrapper around Ranger Admin REST API. Uses Java `HttpClient`, Basic auth.

| Method | HTTP | Notes |
|---|---|---|
| `createPolicy(Map)` | POST `/...policy` | Returns numeric ID from `"id"` field |
| `updatePolicy(id, Map)` | PUT `/...policy/{id}` | Full document replace |
| `deletePolicy(id)` | DELETE `/...policy/{id}` | Accepts 200 or 204 |
| `listPolicies(serviceName)` | GET `/...policy?serviceName=X` | Returns `JsonNode` array |
| `setPolicyEnabled(id, bool, Map)` | delegates to `updatePolicy` | Merges `isEnabled` into current payload |

### `driver/GlueCatalogDiscovery.java`

Populates `databaseTables` at startup when no static `databases` config is provided. Paginates `GetDatabases` then `GetTables` per database. Databases with no tables included with empty list. Catches `EntityNotFoundException` per database (skips silently).

---

## Workload generators

### `workload/WorkloadOrchestrator.java`

Produces a random batch (size 1–10) of `MutationOperation` items.

**Per-operation selection (cumulative roll 0–99):**

| Range | Operation |
|---|---|
| < 30 | CREATE |
| 30–49 | UPDATE existing |
| 50–64 | DISABLE existing |
| 65–79 | ENABLE existing |
| 80–89 | DELETE existing |
| 90–99 | no-op |

Generator selection for CREATE/UPDATE: weighted draw across `generators` list.

### `workload/MutationOperation.java`

Sealed interface with five record subtypes: `CreatePolicy`, `UpdatePolicy`, `DisablePolicy`, `EnablePolicy`, `DeletePolicy`.

### `workload/MutationLog.java`

Thread-safe (`synchronized`) append-only log. In-memory list + on-disk NDJSON. `clear()` clears in-memory only; file never truncated.

### `driver/MutationDriver.java`

Executes `MutationOperation` records against Ranger. Maintains `internalToRangerIdMap` translating simulator-assigned IDs to Ranger numeric IDs. Failed operations log warning and do not abort batch.

### `workload/HivePolicyGenerator.java`

Generates Ranger Hive/lakeformation policies. Methods and gaps each exercises:

| Method | Gap |
|---|---|
| `generateTablePolicy` | Baseline table grant |
| `generateGrantableTablePolicy` | Grant option path (`delegateAdmin=true`) |
| `generateDatabasePolicy` | DB-level permission |
| `generateMultiUserTablePolicy` | Multi-user fan-out |
| `generateColumnPolicy` | TABLE_WITH_COLUMNS path |
| `generateGroupTablePolicy` | Group principal resolution |
| `generateRoleTablePolicy` | Role principal resolution |
| `generateDenyTablePolicy` | Cedar forbid / deny path |
| `generateWildcardTablePolicy` | Wildcard expansion (table `"*"`) |
| `generateUnmappedPrincipalPolicy` | Unmapped principal gap |
| `generateAllAccessTablePolicy` | NOT wired — access type `"all"` |

Column pool: `id, name, value, created_at, status, amount, category, region`. Unmapped principal: `"ghost_user"`.

### Other generators

- **`TrinoServiceGenerator`**: uses `schema` resource key + `catalog: "hive"`; ~20% include `denyPolicyItems`
- **`DataLocationPolicyGenerator`**: always `data_location_access` access type; maps to `DATA_LOCATION_ACCESS`
- **`TagPolicyGenerator`**: `cl_tag` service; produces NO LF grants; validates gap recording; throws if service name doesn't contain `"tag"`
- **`EmrfsPolicyGenerator`**: `sthreeresource` key; maps to S3 Access Grants
- **`EmrSparkPolicyGenerator`**: same db/table/column hierarchy as Hive; `"all"` excluded; only in Phase2 when `validateEmrSpark=true`

---

## Validator layer

### `validator/SimulatorPermission.java`

Record: `(principalArn, resourceType, resourceId, permission, grantable)`.

`resourceType` values: `TABLE`, `TABLE_WITH_COLUMNS`, `DATABASE`, `DATA_LOCATION`, `S3_PREFIX`.

`resourceId` format: `"db.table"` / `"dbname"` / `"s3://bucket/path/"` (trailing slash) / raw S3 subprefix.

### `validator/ValidationResult.java`

Record: `(Outcome outcome, Set<SimulatorPermission> overGrants, Set<SimulatorPermission> underGrants, String description)`.

`Outcome` enum: `PASS`, `TRANSIENT_VIOLATION`, `PERSISTENT_VIOLATION`.

### `validator/Phase1DriftValidator.java`

Detects unexpected changes between consecutive cycles, independent of Ranger state. Computes `overGrants = actual - checkpoint` and `underGrants = checkpoint - actual`. Result logged but does NOT short-circuit Phase2. Checkpoint only updated on clean Phase2.

### `validator/Phase2CorrectnessValidator.java`

Authoritative correctness check: compares actual (filtered to `managedPrincipalArns`) against independently-computed expected set. Produces `PASS` or `TRANSIENT_VIOLATION`.

### `validator/ExpectedPermissionsComputer.java`

Independent oracle that reimplements Ranger-to-LF permission mapping. Must NOT import from `com.amazonaws.policyconverters`.

**`compute(rangerPolicies) → Set<SimulatorPermission>` algorithm:**
1. **`shouldProcess()` filter**: skip disabled, tag service, `policyType=1`, empty resources, non-managed service
2. **Pass 1** (`collectDenies`): builds `Set<DenyKey>` = `(principalArn, resourceId, permission)` from all `denyPolicyItems`
3. **Pass 2** (`processPermitsInto`): resolves principals → ARNs; maps access types → LF permissions (via `SERVICE_ACCESS_MAPS`); expands wildcards via `TableExpander`; emits `SimulatorPermission` records
4. Subtracts denies from permits

**Special normalizations:**
- Column-level resources: only `SELECT` emitted (all others stripped) — mirrors LF `TABLE_WITH_COLUMNS` behavior
- Table-level `SELECT`: promoted from `TABLE` → `TABLE_WITH_COLUMNS` to match `ListPermissions` return type

**`SERVICE_ACCESS_MAPS`** (per-service access type → LF permission name):

| Service | Key access types |
|---|---|
| `lakeformation` | `select`→SELECT, `insert`→INSERT, `delete`→DELETE, `describe`→DESCRIBE, `alter`→ALTER, `drop`→DROP, `create_database`→CREATE_DATABASE, `create_table`→CREATE_TABLE, `update`→INSERT, `create`→CREATE_TABLE, `read`→SELECT, `write`→INSERT, `all`→SELECT+INSERT+DELETE+ALTER+DROP+DESCRIBE, `data_location_access`→DATA_LOCATION_ACCESS |
| `hive` | `select,read`→SELECT; `update,write`→INSERT; `create`→CREATE_TABLE; `drop,alter`→DROP,ALTER |
| `trino` | `select`→SELECT; `insert`→INSERT; `delete`→DELETE; `create`→CREATE_TABLE; `drop`→DROP; `alter`→ALTER; `use,show`→DESCRIBE |
| `amazon-emr-spark` | same as hive subset (no `all`) |

### `validator/LFPermissionsFetcher.java`

Full paginated `ListPermissions` scan. Normalizes `PrincipalResourcePermissions` → `SimulatorPermission`.

**Resource ID normalization:**
- Data location: `arn:aws:s3:::bucket/path` → `s3://bucket/path/` (trailing slash)
- Table: `databaseName + "." + tableName` (or `"*"` if name null)
- Database: bare `name`
- TableWithColumns: `databaseName + "." + name`

### `validator/S3AgPermissionsFetcher.java`

Paginates `ListAccessGrants`. Returns `SimulatorPermission` with `resourceType="S3_PREFIX"`. Returns empty set if `instanceArn` is blank or API returns 404.

### `validator/TableExpander.java`

Functional interface: `expand(database, tablePattern) → List<String>`. Wired in `SimulatorMain` as a lambda converting glob → regex and filtering the discovered table list.

---

## Mutation / remediation

### `remediation/RemediationRunner.java`

Waits for one full sync cycle to complete after a violation. Delegates to `CycleWaiter.waitForCycleAfter(violationCycle)`.

### `status/CycleWaiter.java`

Polls `SyncServiceStatusClient.fetchStatus()` every 5 seconds until `lastCompletedCycle > targetCycle` or timeout. Throws `CycleTimeoutException` on timeout.

### `status/SyncServiceStatusClient.java`

HTTP GET to `http://<host>:<port>/status`. Deserializes response to `StatusResponse(lastCompletedCycle, lastCompletedWildcardRefreshCycle, state)`.

---

## Bundle / reporting

### `remediation/ReproductionBundle.java`

Record: `detectedAt`, `violationDetectedAfterCycle`, `lastSuccessfulCycle`, `mutations (List<MutationOperation>)`, `rangerSnapshotJson`, `lfActual (Set<SimulatorPermission>)`, `lfExpected (Set<SimulatorPermission>)`, `validationResult`.

### `remediation/BundleWriter.java`

Writes bundle to timestamped directory `violation_<yyyy-MM-dd_HH-mm-ss>` under `baseDir`.

| File | Contents |
|---|---|
| `mutations.json` | All `MutationOperation` records |
| `ranger-snapshot.json` | All Ranger policies at violation time |
| `lf-actual.json` | Actual `SimulatorPermission` set |
| `lf-expected.json` | Expected `SimulatorPermission` set |
| `diff.json` | `{overGrants, underGrants, description}` |
| `cycle-sequence.json` | `{violationDetectedAfterCycle, lastSuccessfulCycle}` |
| `README.txt` | Human-readable reproduction instructions |

### `alert/AlertEmitter.java`

Interface: `emit(ValidationResult, ReproductionBundle)`.
- `LogAlertEmitter`: logs at WARN
- `NoOpAlertEmitter`: discards (for tests)

Called twice per violation: `TRANSIENT_VIOLATION` if self-healed; `PERSISTENT_VIOLATION` if not or on timeout.
