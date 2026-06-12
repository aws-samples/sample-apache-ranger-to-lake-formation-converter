# ApacheRangerToLF — Design Index

Living architectural reference. Update this index and the relevant component doc whenever new classes are added or existing ones are significantly changed.

## Component Documents

| File | Covers |
|---|---|
| [01-app-entry-points.md](01-app-entry-points.md) | `SyncServiceMain`, `ConversionServerMain` — startup wiring, env overrides, lifecycle |
| [02-sync-pipeline.md](02-sync-pipeline.md) | `SyncService`, `ReverseSyncService`, `DriftDetector`, `CheckpointStore`, `DeadLetterLogger` |
| [03-config.md](03-config.md) | All config POJOs (`SyncConfig`, `ReverseSyncConfig`, `ServerConfig`, `AwsConfig`, `RetryConfig`, etc.) |
| [04-conversion-pipeline.md](04-conversion-pipeline.md) | `RangerToCedarConverter`, `PolicyConverter`, `CatalogResolver`, `GlobPatternDetector`, Cedar layer |
| [05-ranger-services.md](05-ranger-services.md) | `BaseRangerService`, all six concrete services, `RangerPlugin`, all `SourcePolicyAdapter` implementations |
| [06-lf-client.md](06-lf-client.md) | `LakeFormationClient`, `LFPermissionFetcher`, `DryRunLakeFormationClient`, batching/retry details |
| [07-principal-mapping.md](07-principal-mapping.md) | `PrincipalMapper` interface, `StaticPrincipalMapper`, `IdentityCenterPrincipalMapper`, `CompositePrincipalMapper`, `PassthroughPrincipalMapper`, `PrincipalMapperFactory` |
| [08-s3-access-grants.md](08-s3-access-grants.md) | `S3AccessGrantsClient`, `CedarToS3AccessGrantsConverter`, S3AG model classes |
| [09-models-and-reporting.md](09-models-and-reporting.md) | All model POJOs, `GapReporter`, `GapReport`, `DriftReport`, `BatchResult`, `SyncCheckpoint`, etc. |
| [10-lf-resources.md](10-lf-resources.md) | `LFResource`, `LFPermission`, `LFPermissionOperation`, `PermissionFilter`, `AwsContext`, `ArnParser` |
| [11-simulator.md](11-simulator.md) | Full simulator architecture: driver, workload generators, validators, mutation/remediation, bundle writer |

## Key Data Flows

### Forward sync (Ranger → LF)
```
BaseRangerService.getLatestPolicies()
  → RangerToCedarConverter.convert(policies)        [Ranger → Cedar]
  → CedarToLFConverter.convert(cedarPolicySet)       [Cedar → LFPermissionOperation list]
  → SyncService.computeDiff(prev, current)           [diff; PermissionKey identity]
  → LakeFormationClient.applyBatch(delta)            [BatchGrant/Revoke ≤20 at a time]
  → CheckpointStore.save(version, cedarText)
```

### Reverse sync / reconciliation (LF → corrective ops)
```
LFPermissionFetcher.fetchPermissions(catalogId, filter)   [actual LF state]
  + CedarToLFConverter.convert(lastCedarPolicySet)         [desired LF state]
  → DriftDetector.computeDrift(desired, actual, filter)
  → ReverseSyncService.orderCorrectiveOperations()         [REVOKEs first]
  → LakeFormationClient.grantPermission / revokePermission
```

### EMRFS path (Ranger → S3 Access Grants)
```
EmrfsRangerService → RangerToCedarConverter → CedarPolicySet
  → CedarToS3AccessGrantsConverter.convert()
  → S3AccessGrantsClient.applyBatch()
```

## PermissionKey Identity Rule

Two `LFPermissionOperation` objects represent the **same** permission if all of these match:
- `principalArn`
- `LFResource` (catalogId, databaseName, tableName, columnNames, dataLocationPath, allTables, rowFilterExpression)
- `permissions` (Set<LFPermission>)
- `grantable` (boolean)

Operation type (`GRANT`/`REVOKE`) and `sourcePolicyId` are **excluded** from identity comparison. This is true in both `SyncService.PermissionKey` and `DriftDetector.PermissionKey` (they are structurally identical inner classes).

## Reuse Checklist

Before adding a new class, check whether an existing one already does the job:

| Need | Existing component |
|---|---|
| Fetch actual LF permissions | `LFPermissionFetcher.fetchPermissions()` |
| Compute drift between desired and actual | `DriftDetector.computeDrift()` |
| Apply corrective GRANTs and REVOKEs | `ReverseSyncService.execute()` |
| Expand Glue wildcards | `CatalogResolver.expandTables/Databases/Columns()` |
| Map Ranger access type → LF permission | `AccessTypeMapper` or adapter's `mapAccessTypeToCedarActions()` |
| Map Ranger principal → IAM ARN | `PrincipalMapper` (any impl) |
| Parse Glue/S3 ARN | `ArnParser.parseGlueArn()` / `parseS3Arn()` |
| Retry with exponential backoff | `LakeFormationClient.executeWithRetry()` or `RetryConfig` |
| Persist sync state | `CheckpointStore` |
| Log permanently failed operations | `DeadLetterLogger` |
| Record unsupported Ranger features | `GapReporter.recordGap()` |
| Normalize `s3a://`/`s3n://` to `s3://` | `RangerToCedarConverter.normalizeS3Location()` |
