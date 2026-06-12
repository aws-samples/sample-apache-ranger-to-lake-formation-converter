# Conversion Pipeline

## Overview

Two conversion paths exist:

| Path | Used by | Output |
|---|---|---|
| **Cedar path** (primary) | `SyncService` | `Ranger → CedarPolicySet → LFPermissionOperation list` |
| **Direct LF path** (assessment) | `AssessmentMain`, `PolicyConverter` | `Ranger → LFPermissionOperation list` (no Cedar roundtrip) |

---

## `ranger/RangerToCedarConverter.java`

**Purpose:** Converts a batch of `RangerPolicy` objects to a validated `CedarPolicySet`. Primary Stage 1 of the sync pipeline.

### Key fields

| Field | Type | Notes |
|---|---|---|
| `adapterRegistry` | `Map<String, SourcePolicyAdapter>` | keyed by service type string |
| `principalMapper` | `PrincipalMapper` | resolves Ranger user/group/role → IAM ARN |
| `catalogResolver` | `CatalogResolver` | expands wildcard patterns against Glue |
| `gapReporter` | `GapReporter` | records unsupported features |
| `schemaProvider` | `CedarSchemaProvider` | validates Cedar text |
| `skipCedarValidation` | `boolean` | bypass schema validation for offline mode |

### Cedar output format per statement
```
@source("serviceType:policyId")
[@denyException("true")]
[@grantable("true")]
permit|forbid(
    principal == DataCatalog::Principal::"<iam-arn>",
    action    == DataCatalog::Action::"<ACTION>",
    resource  == DataCatalog::Database|Table|Column|DataLocation::"<arn>"
)[when { resource.rowFilter == "<expr>" }];
```

### Wildcard handling

| Pattern | Behavior |
|---|---|
| Bare `*` on table | `PolicyConverter.buildResourceOperations()` handles as `LFResource.allTablesResource()` → LF `TableWildcard` |
| `prefix_*`, `db_?` | `CatalogResolver.expandDatabases/Tables/Columns()` queries Glue with paginated scan + regex |
| `isExcludes=true` | Records `EXCLUDES_PATTERN` gap, skips entire policy |
| Glue unavailable | Returns empty list; gap recorded |

**Resource level selection:** `determineResourceLevel()` picks the deepest non-empty resource key (`sthreeresource > url > datalocation > column > table > database`). `promoteResourceLevel()` promotes column-level to table or database when all wildcard values are present.

### Deny/exception handling

| Scenario | Cedar output | Gap recorded |
|---|---|---|
| Deny items | `forbid(...)` Cedar statements | `DENY_POLICY` |
| Deny exceptions | `permit(...) @denyException("true")` | `DENY_EXCEPTION` |
| Row filters | `when { resource.rowFilter == "..." }` | None (supported) |
| `delegateAdmin=true` | `@grantable("true")` annotation | None (supported) |
| Tag-based policy | Skipped entirely | `TAG_BASED_POLICY` |
| Data masking | Skipped entirely | `DATA_MASKING` |
| Validity schedule | Conversion continues (dropped) | `VALIDITY_SCHEDULE` |
| Security zone | Conversion continues (dropped) | `SECURITY_ZONE` |
| Custom condition | Conversion continues (dropped) | `CUSTOM_CONDITION` |
| Non-S3 location | Skipped | `UNMAPPED_RESOURCE` |

### Validation / fallback

`parseAndValidate()` first tries the full `PolicySet`. On schema failure, `rebuildExcludingInvalid()` validates each statement individually, records `SCHEMA_VALIDATION_FAILURE` gaps for bad ones, returns the valid subset.

---

## `ranger/PolicyConverter.java`

**Purpose:** Direct Ranger → `LFPermissionOperation` path. Used by the assessment tool. Bypasses Cedar entirely.

### Key differences from `RangerToCedarConverter`

- Produces `LFPermissionOperation` directly (no Cedar intermediate)
- Does NOT handle deny items, deny exceptions, or row filters (records gaps only)
- `delegateAdmin=true` → records `DELEGATED_ADMIN` gap (no grant option support in direct path)
- Bare `*` on table → `LFResource.allTablesResource()` (uses the `allTables=true` field directly)
- `ConversionResult` batches success/skipped counts with per-policy exception handling

---

## `ranger/CatalogResolver.java`

**Purpose:** Expands Ranger glob patterns into concrete Glue catalog names. On any AWS exception, returns empty list (graceful failure).

### Key methods

| Method | Glue API | Notes |
|---|---|---|
| `expandDatabases(pattern)` | `GetDatabases` (paginated) | Filters by compiled regex |
| `expandTables(database, pattern)` | `GetTables(db)` (paginated) | Filters by compiled regex |
| `expandColumns(database, table, pattern)` | `GetTable` + `StorageDescriptor.columns` + `partitionKeys` | Filters |

**Wildcard-to-regex:** `*` → `.*`, `?` → `.`; all Java regex metacharacters escaped first. See `toRegexPattern()` static method.

**`PassthroughCatalogResolver`:** No-op subclass; returns the literal pattern as a singleton. Used in offline/assessment mode when no AWS credentials are available.

---

## `ranger/GlobPatternDetector.java`

**Purpose:** Identifies Ranger policies with non-trivial glob patterns (containing `*` or `?` but NOT bare `*`), for `WildcardRefreshScheduler`.

**Why bare `*` is excluded:** LF `TableWildcard` handles it natively. Only partial wildcards need periodic re-expansion.

### Key static methods

- `isGlobPattern(String value) → boolean`: `true` if contains `*` or `?` AND is not exactly `"*"`
- `hasGlobPatterns(RangerPolicy) → boolean`: checks `database`, `table`, `column` resource keys
- `filterGlobPolicies(List<RangerPolicy>) → List<RangerPolicy>`: filters list to glob-bearing only

---

## Cedar layer

### `cedar/CedarPolicySet.java`

Wraps the cedar-java SDK `PolicySet`. Key methods:

- `static fromCedarString(String) throws InternalException` — parses via `PolicySet.parsePolicies()`
- `Set<String> getPrincipals()` — extracts all `principal == ...` values via regex
- `String toCedarString()` — joins `policy.getSource()` for each policy
- `int getPermitCount()` / `int getForbidCount()`
- `List<String> getSourcePolicyIds()` — extracts `@source("...")` annotation values

### `cedar/CedarToLFConverter.java`

**Purpose:** Partial evaluation of Cedar permit/forbid/deny-exception semantics → `LFPermissionOperation` list.

**Partial evaluation algorithm (per principal):**
1. Split Cedar text on `;` (bracket-depth aware)
2. Parse each statement: effect, principal, action, resource, sourceId, isDenyException, rowFilter, grantable
3. Group by principal → collect `permits`, `forbidSet (ActionResourceKey)`, `denyExceptionSet (ActionResourceKey)`
4. For each permit: if `forbidSet.contains(key) && !denyExceptionSet.contains(key)` → skip (deny wins); else create GRANT op
5. `s3:` prefixed actions silently skipped (not LF actions)
6. Unknown actions → `UNSUPPORTED_ACTION` gap

**Resource parsing (`parseResourceIdentifier`):**
- `arn:aws:s3` → `ArnParser.parseS3Arn()` → `LFResource` with `dataLocationPath`
- Otherwise → `ArnParser.parseGlueArn()` → `LFResource` with catalogId/db/table/column
- Non-ARN identifiers → `UNMAPPED_RESOURCE` gap

**Inner classes:**
- `ParsedStatement`: struct with all parsed fields
- `ActionResourceKey`: value-equality `(action, resourceId)` for forbid/denyException matching

### `cedar/CedarSchemaProvider.java`

Loads `DataCatalog` schema from classpath (`cedar/datacatalog.cedarschema`). Validates `PolicySet` objects against it via `BasicAuthorizationEngine`.

**Methods:** `loadSchema()`, `getSchema()`, `validate(cedarText) → List<String>`, `validatePolicySet(PolicySet) → List<String>`.

### `cedar/SourcePolicyAdapter.java`

Interface for service-type-specific Ranger-to-Cedar mapping.

| Method | Signature | Notes |
|---|---|---|
| `getServiceType()` | `→ String` | e.g. `"lakeformation"`, `"hive"` |
| `mapAccessTypeToCedarActions(accessType)` | `→ Set<String>` | single access type |
| `mapAccessTypeToCedarActions(accessType, resourceLevel)` | `→ Set<String>` | resource-level-aware override |
| `buildEntityRef(policy, resourceLevel)` | `→ CedarEntityRef` | from full policy |
| `buildPrincipalRef(principalId)` | `→ String` | formats IAM ARN as Cedar principal |
| `getAwsContext()` | `→ Optional<AwsContext>` | |
| `shouldProcessPolicy(policy)` | `→ boolean` (default true) | Presto/Trino override for catalog filtering |

### `cedar/CedarEntityRef.java`

Final pair `(String entityType, String entityId)`. `toString()` → `DataCatalog::Database::"arn:..."`.

---

## `lakeformation/ArnParser.java`

Static utility for parsing AWS ARNs.

| Method | Returns | Notes |
|---|---|---|
| `isArn(String)` | `boolean` | checks `startsWith("arn:")` |
| `parseGlueArn(String)` | `GlueResourceRef` | auto-detects database/table/column |
| `parseS3Arn(String)` | `S3ResourceRef` | |
| `parseDatabaseArn`, `parseTableArn`, `parseColumnArn` | `GlueResourceRef` | typed variants |

**`GlueResourceRef`:** `region`, `accountId`, `databaseName`, `tableName`, `columnName`.

**`S3ResourceRef`:** `bucket`, `path`.

All throw `IllegalArgumentException` on malformed input; `CedarToLFConverter` catches and records `UNMAPPED_RESOURCE` gaps.
