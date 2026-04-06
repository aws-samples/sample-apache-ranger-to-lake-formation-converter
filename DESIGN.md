# Design Document: Ranger Lake Formation Sync Plugin

## Overview

This plugin bridges Apache Ranger access control policies to AWS Lake Formation permissions. It converts Ranger's policy model (allow/deny rules on users, groups, roles for Glue Data Catalog resources) into Lake Formation's grant/revoke permission model via an intermediate Cedar policy representation.

## Architecture

```
Ranger Admin                    Sync Plugin                         AWS
┌──────────┐   policy refresh   ┌─────────────────────────────┐
│ Policies ├───────────────────►│ LakeFormationPlugin         │
│          │                    │   │                         │
│ Service  │                    │   ▼                         │
│ Def:     │                    │ RangerToCedarConverter      │
│ lakeform │                    │   │  (+ CatalogResolver     │
│          │                    │   │     PrincipalMapper      │
└──────────┘                    │   │     GapReporter)         │
                                │   ▼                         │
                                │ CedarPolicySet              │
                                │   │                         │
                                │   ▼                         │
                                │ CedarToLFConverter          │
                                │   │                         │
                                │   ▼                         │
                                │ SyncService.computeDiff()   │
                                │   │                         │
                                │   ▼                         │
                                │ LakeFormationClient         │──► LF API
                                │   or                        │
                                │ DryRunLakeFormationClient   │──► JSON files
                                └─────────────────────────────┘
```

### Conversion Pipeline

1. **Ranger → Cedar**: `RangerToCedarConverter` transforms Ranger policies into Cedar policy statements. Each policy item (user + access type + resource) becomes a Cedar `permit` or `forbid` statement with `@source` annotations preserving the Ranger policy ID. The `RangerLFServiceAdapter` maps Ranger access types to Cedar action names and builds Glue ARN-formatted entity identifiers. `CatalogResolver` expands wildcard resource patterns against the Glue Data Catalog. `PrincipalMapper` resolves Ranger users/groups/roles to IAM ARNs.

2. **Cedar → LF**: `CedarToLFConverter` parses the validated Cedar policy set back into `LFPermissionOperation` objects (GRANT/REVOKE with principal, resource, and permissions). It resolves permit/forbid/deny-exception semantics to produce effective grants.

3. **Diff & Apply**: `SyncService.computeDiff()` compares the current LF operations against the previous snapshot to produce a minimal delta (new grants + revocations). The delta is applied via `LakeFormationClient.applyBatch()` with per-policy atomicity and rollback on failure.

### Cedar Schema

The Cedar schema (`src/main/resources/cedar/datacatalog.cedarschema`) defines the valid entity types and action-resource constraints:

| Action | Valid Resources |
|--------|----------------|
| SELECT | Table, Column |
| INSERT | Table |
| DELETE | Table |
| DESCRIBE | Catalog, Database, Table |
| ALTER | Database, Table |
| DROP | Database, Table |
| CREATE_TABLE | Database |
| CREATE_DATABASE | Catalog |
| DATA_LOCATION_ACCESS | DataLocation |

### Access Type Mapping

The `RangerLFServiceAdapter` maps Ranger access types to Cedar actions:

| Ranger Access Type | Cedar Action | Notes |
|-------------------|--------------|-------|
| `select` | SELECT | |
| `update` | INSERT | |
| `create` | CREATE_TABLE | |
| `drop` | DROP | |
| `alter` | ALTER | |
| `read` | SELECT | Alias |
| `write` | INSERT | Alias |
| `all` | SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE | Expanded |
| `datalocation` | DATA_LOCATION_ACCESS | |
| `data_location_access` | DATA_LOCATION_ACCESS | |


## Dry-Run Mode

The `DryRunLakeFormationClient` extends `LakeFormationClient` and overrides `applyBatch()` to serialize operations to JSON files instead of calling the AWS Lake Formation API. This enables:

- Integration testing without AWS credentials
- Human review of what the pipeline would do before enabling live mode
- Debugging conversion issues by inspecting the JSON output

### Activation

Set `DRY_RUN_ENABLED=true` (case-insensitive) as an environment variable. Optionally set `DRY_RUN_OUTPUT_DIR` (defaults to `./dry-run-output`). The wiring is in `SyncServiceMain.startSyncService()`.

### Output Format

Each `applyBatch()` call writes a `dry-run-NNN.json` file:

```json
{
  "timestamp": "2026-04-06T10:30:00Z",
  "sequenceNumber": 1,
  "operations": [
    {
      "operationType": "GRANT",
      "sourcePolicyId": "42",
      "principalArn": "arn:aws:iam::123456789012:role/analyst",
      "resource": {
        "catalogId": "123456789012",
        "databaseName": "analytics",
        "tableName": "events"
      },
      "permissions": ["SELECT"],
      "grantable": false
    }
  ]
}
```

### Design Decisions

- **Extend rather than extract interface**: `DryRunLakeFormationClient` extends `LakeFormationClient` and passes `null` for the SDK client. Since `applyBatch()` is fully overridden, the parent's SDK client is never invoked. This avoids refactoring all existing code that references the concrete class.
- **AtomicInteger sequence counter**: Ensures monotonic filenames across concurrent calls.
- **Always-success BatchResult**: The dry-run client returns zero failures and zero rollbacks since no real API calls are made.

## Integration Test Infrastructure

### Test Pipeline

Integration tests exercise the full conversion chain against a live Docker Ranger Admin instance:

```
Test Code                    Docker Ranger          Sync Pipeline (in-process)
┌──────────┐  REST API       ┌──────────┐
│ Create   ├────────────────►│ Ranger   │
│ Policy   │                 │ Admin    │
└──────────┘                 │ :6080    │
                             └────┬─────┘
┌──────────┐  GET policies        │
│ Trigger  ├──────────────────────┘
│ Sync     │
│          ├──► RangerToCedarConverter
│          ├──► CedarToLFConverter
│          ├──► SyncService.computeDiff()
│          ├──► DryRunLakeFormationClient
│          │         │
│          │         ▼
│ Assert   │◄── dry-run-NNN.json
└──────────┘
```

### Base Class: DryRunPipelineIT

All integration tests extend `DryRunPipelineIT` which handles:

- **@BeforeAll**: Verifies the `lakeformation` service definition exists in Ranger Admin, updates the `implClass` for Docker compatibility, and creates a service instance if needed.
- **@BeforeEach**: Creates a temp output directory, wires up the full pipeline (RangerToCedarConverter → CedarToLFConverter → SyncService with DryRunLakeFormationClient), and starts the SyncService.
- **@AfterEach**: Deletes all created policies (logs warning on failure), deletes output files, stops the SyncService.
- **@AfterAll**: Flushes the JSON audit log to `logs/`.

Key helpers:
- `createAndTrackPolicy(json)` — POST to Ranger, track for cleanup
- `updatePolicy(id, json)` — PUT to Ranger
- `deletePolicyAndUntrack(id)` — DELETE from Ranger
- `triggerSync()` — GET policies from Ranger, build ServicePolicies, call `syncService.onPoliciesUpdated()`
- `readDryRunOutputs()` — parse all `dry-run-*.json` files
- `clearDryRunOutputs()` — clear output between sync cycles in the same test

### Test Scenarios

| Test Class | Scenario | Resource Level |
|------------|----------|----------------|
| DatabaseGrantPolicyIT | ALTER grant on database | Database |
| TableGrantPolicyIT | SELECT + DROP grant on table | Table |
| ColumnGrantPolicyIT | SELECT grant on column | Column |
| DataLocationGrantPolicyIT | DATA_LOCATION_ACCESS grant + delete/revoke | DataLocation |
| PolicyUpdateDiffIT | Add permission (diff shows new GRANT), remove user (diff shows REVOKE) | Table |
| PolicyDeletionRevokeIT | Delete policy → REVOKE for all previous grants | Table |
| MultiUserPolicyIT | Multi-user grant, partial user removal | Table |
| MultiPolicyInteractionIT | Two independent policies, selective deletion | Table |
| WildcardPolicyIT | Wildcard `*` table and database patterns | Database, Table |
| DisabledPolicyIT | Disabled policy handling, delete active policy | Table |
| UnmappedPrincipalPolicyIT | Unmapped user skipped, mixed mapped/unmapped | Table |


### Audit Log

Each integration test class produces a JSON audit log at `logs/it-audit-<TestClass>.json`. The log records every Ranger input action and the resulting Lake Formation output operations in a human-reviewable format:

```json
[
  {
    "test": "DatabaseGrantPolicyIT.testDatabaseSelectGrant",
    "timestamp": "2026-04-06T17:12:23.601Z",
    "steps": [
      {
        "timestamp": "2026-04-06T17:12:23.581Z",
        "input": {
          "action": "ranger",
          "operation": "CREATE_POLICY",
          "policyId": 157,
          "policy": { "name": "test-db-alter-analyst", "..." : "..." }
        }
      },
      {
        "timestamp": "2026-04-06T17:12:23.595Z",
        "output": [
          {
            "action": "lf",
            "operation": "GRANT",
            "principal": "arn:aws:iam::123456789012:role/analyst",
            "permissions": "ALTER",
            "api_call": {
              "api": "lakeformation:GrantPermissions",
              "principal": "arn:aws:iam::123456789012:role/analyst",
              "resource": {
                "type": "Database",
                "catalogId": "123456789012",
                "databaseName": "test_db"
              },
              "permissions": "ALTER"
            }
          }
        ]
      }
    ]
  }
]
```

The `api_call` field shows exactly what Lake Formation API would be called with what parameters, making it easy for a human to verify correctness.

## Property-Based Tests

The project uses jqwik 1.7.4 for property-based testing. Each property validates a universal correctness invariant:

| Property | Test Class | What It Validates |
|----------|-----------|-------------------|
| Round-trip serialization | DryRunOutputRoundTripPropertyTest | Serialize → deserialize produces equal operations |
| Always-success BatchResult | DryRunBatchResultPropertyTest | Dry-run returns zero failures, zero rollbacks |
| Monotonic sequence numbering | DryRunSequencePropertyTest | N calls produce files 001..N with matching sequence numbers |
| Deletion-revocation symmetry | DeletionRevocationSymmetryPropertyTest | Empty current snapshot produces REVOKEs matching all previous GRANTs |

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Dry-run output directory doesn't exist | Created automatically |
| Dry-run JSON write fails | RuntimeException propagated to SyncService |
| Ranger REST API returns non-2xx | RuntimeException with status code and body |
| Policy cleanup fails in @AfterEach | Warning logged, test not failed |
| Unmapped principal in policy | Skipped with warning log, no GRANT produced |
| Cedar schema validation rejects statement | Statement excluded, gap recorded |
| Disabled policy in Ranger | Processed normally (Ranger REST API returns disabled policies) |
