# Package Reorganization Design

## Problem

The current package structure mixes concerns across `ranger/`, `lakeformation/`, `server/`, and top-level `cedar/`. Key issues:

1. Two entry points (`SyncServiceMain` in `ranger/`, `ConversionServerMain` in `server/`) doing similar things in different packages
2. Config classes split across three packages (`lakeformation/model/`, `ranger/config/`, `server/`)
3. Cedar code split across three packages (`cedar/`, `lakeformation/cedar/`, `ranger/cedar/`)
4. Sync orchestration split between `ranger/sync/` and `lakeformation/sync/`
5. `ranger/` package references LakeFormation directly (e.g. `LakeFormationPlugin`, `RangerLFServiceAdapter`, `LakeFormationResourceLookupService`)
6. `PrincipalMapper` lives in `ranger/` but is a LakeFormation concern (maps Ranger principals to IAM ARNs for LF)
7. General config classes like `RangerConnectionConfig` and `RetryConfig` live in `lakeformation/model/` despite not being LF-specific

## Design Principles

- Organize by responsibility, not by source/target system
- `ranger/` should have zero references to LakeFormation — it only knows about Ranger policies and Cedar
- `lakeformation/` owns the IAM/LF-specific mapping, including principal mapping
- Cedar is the bridge language between Ranger and LakeFormation
- Entry points and lifecycle management belong together
- Config classes belong together regardless of which subsystem they configure

## Proposed Package Structure

```
com.amazonaws.policyconverters/
│
├── app/                              # Entry points & process lifecycle
│   ├── SyncServiceMain.java          # (from ranger/SyncServiceMain)
│   ├── ConversionServerMain.java     # (from server/ConversionServerMain)
│   ├── ServiceDefInstallerMain.java  # (from ranger/ServiceDefInstallerMain)
│   ├── ServerLifecycle.java          # (from server/ServerLifecycle)
│   └── SyncCycleExecutor.java        # (from server/SyncCycleExecutor)
│
├── config/                           # All configuration loading & validation
│   ├── SyncConfig.java               # (from lakeformation/model/SyncConfig)
│   ├── ServerConfig.java             # (from server/ServerConfig)
│   ├── AwsConfig.java                # (from lakeformation/model/AwsConfig)
│   ├── RangerConnectionConfig.java   # (from lakeformation/model/RangerConnectionConfig)
│   ├── PrincipalMappingConfig.java   # (from lakeformation/model/PrincipalMappingConfig)
│   ├── RetryConfig.java              # (from lakeformation/model/RetryConfig)
│   ├── ReverseSyncConfig.java        # (from lakeformation/model/ReverseSyncConfig)
│   ├── ConfigLoader.java             # (from ranger/config/ConfigLoader)
│   ├── ConfigValidator.java          # (from ranger/config/ConfigValidator)
│   └── ServerConfigLoader.java       # (from server/ServerConfigLoader)
│
├── cedar/                            # Cedar policy language — the bridge layer
│   ├── CedarPolicySet.java           # (from cedar/CedarPolicySet)
│   ├── CedarSchemaProvider.java      # (from cedar/CedarSchemaProvider)
│   ├── CedarEntityRef.java           # (from cedar/CedarEntityRef)
│   ├── SourcePolicyAdapter.java      # (from cedar/SourcePolicyAdapter)
│   └── CedarToLFConverter.java       # (from lakeformation/cedar/CedarToLFConverter)
│
├── ranger/                           # Ranger-specific: plugin, policy conversion to Cedar
│   │                                 # NO references to LakeFormation in this package
│   ├── RangerPlugin.java             # (from ranger/sync/LakeFormationPlugin — renamed)
│   ├── RangerToCedarConverter.java   # (from ranger/cedar/RangerToCedarConverter)
│   ├── RangerServiceAdapter.java     # (from ranger/cedar/RangerLFServiceAdapter — renamed)
│   ├── CatalogResolver.java          # (from ranger/catalog/CatalogResolver)
│   ├── AccessTypeMapper.java         # (from ranger/converter/AccessTypeMapper)
│   ├── PolicyConverter.java          # (from ranger/converter/PolicyConverter)
│   ├── ConversionResult.java         # (from ranger/converter/ConversionResult)
│   └── service/                      # Ranger Admin service definition management
│       ├── ServiceDefinitionInstaller.java
│       ├── ServiceDefinitionInstallException.java
│       └── ResourceLookupService.java  # (from ranger/service/LakeFormationResourceLookupService — renamed)
│
├── lakeformation/                    # LF API client, IAM mapping, LF-specific models
│   ├── LakeFormationClient.java      # (from lakeformation/client/LakeFormationClient)
│   ├── DryRunLakeFormationClient.java
│   ├── LakeFormationClientException.java
│   ├── LFPermissionFetcher.java      # (from lakeformation/client/LFPermissionFetcher)
│   ├── BatchResult.java              # (from lakeformation/client/BatchResult)
│   ├── PrincipalMapper.java          # (from ranger/mapper/PrincipalMapper — moved here)
│   ├── ArnParser.java                # (from lakeformation/cedar/ArnParser)
│   ├── GlueResourceRef.java          # (from lakeformation/cedar/GlueResourceRef)
│   ├── S3ResourceRef.java            # (from lakeformation/cedar/S3ResourceRef)
│   ├── AwsContext.java               # (from lakeformation/cedar/AwsContext)
│   ├── LFPermission.java             # (from lakeformation/model/LFPermission)
│   ├── LFPermissionOperation.java    # (from lakeformation/model/LFPermissionOperation)
│   ├── LFResource.java               # (from lakeformation/model/LFResource)
│   └── PermissionFilter.java         # (from lakeformation/model/PermissionFilter)
│
├── sync/                             # Forward + reverse sync orchestration
│   ├── SyncService.java              # (from ranger/sync/SyncService)
│   ├── CheckpointStore.java          # (from ranger/sync/CheckpointStore)
│   ├── SyncCheckpoint.java           # (from ranger/sync/SyncCheckpoint)
│   ├── ReverseSyncService.java       # (from lakeformation/sync/ReverseSyncService)
│   ├── DriftDetector.java            # (from lakeformation/sync/DriftDetector)
│   └── DeadLetterLogger.java         # (from lakeformation/client/DeadLetterLogger)
│
├── model/                            # Shared domain models & results
│   ├── DriftReport.java              # (from lakeformation/model/DriftReport)
│   ├── DriftResult.java              # (from lakeformation/model/DriftResult)
│   ├── ReverseSyncResult.java        # (from lakeformation/model/ReverseSyncResult)
│   ├── GapEntry.java                 # (from lakeformation/model/GapEntry)
│   ├── GapReport.java                # (from lakeformation/model/GapReport)
│   ├── SyncCycleResult.java          # (from server/SyncCycleResult)
│   ├── DryRunOutput.java             # (from lakeformation/client/DryRunOutput)
│   └── ReverseSyncDryRunOutput.java  # (from lakeformation/client/ReverseSyncDryRunOutput)
│
├── reporting/                        # Gap reporting, metrics, structured logging
│   ├── GapReporter.java              # (from lakeformation/reporter/GapReporter)
│   ├── MetricsEmitter.java           # (from server/MetricsEmitter)
│   └── StructuredErrorLogger.java    # (from lakeformation/logging/StructuredErrorLogger)
│
└── deploy/                           # Deploy template utilities (unchanged)
    ├── DeployTemplateUtils.java
    └── TemplateFilter.java
```

## Renames

These classes are renamed to remove LakeFormation references from the `ranger/` package:

| Current Name | New Name | Reason |
|---|---|---|
| `LakeFormationPlugin` | `RangerPlugin` | Ranger package should not reference LF |
| `RangerLFServiceAdapter` | `RangerServiceAdapter` | Remove LF from name |
| `LakeFormationResourceLookupService` | `ResourceLookupService` | Remove LF from name; context is clear from package |

## Moves Across Package Boundaries

These are the key cross-boundary moves that change ownership:

| Class | From | To | Reason |
|---|---|---|---|
| `PrincipalMapper` | `ranger/mapper/` | `lakeformation/` | Maps to IAM ARNs — that's an LF concern |
| `CedarToLFConverter` | `lakeformation/cedar/` | `cedar/` | Part of the Cedar bridge layer |
| `ArnParser`, `GlueResourceRef`, `S3ResourceRef`, `AwsContext` | `lakeformation/cedar/` | `lakeformation/` | AWS/LF-specific, not Cedar |
| `DeadLetterLogger` | `lakeformation/client/` | `sync/` | Used by sync orchestration, not the LF client |
| `SyncService`, `CheckpointStore`, `SyncCheckpoint` | `ranger/sync/` | `sync/` | Sync orchestration is cross-cutting |
| `SyncConfig`, `AwsConfig`, etc. | `lakeformation/model/` | `config/` | Config classes belong together |
| `DriftReport`, `GapEntry`, etc. | `lakeformation/model/` | `model/` | Shared domain models |

## File Count

- ~60 source files to move/rename
- ~60 corresponding test files to move/rename
- All import updates handled by IDE tooling (`smartRelocate` / `semanticRename`)

## Risks

- Integration tests in `src/integration-test/` reference current package paths
- `pom.xml` references `ConversionServerMain` as the jar main class — needs updating
- Dockerfile or deploy scripts may reference fully-qualified class names
- Any reflection-based or string-based class references won't be caught by IDE refactoring

## Out of Scope

- No behavioral changes — this is a pure structural refactor
- No new classes or interfaces introduced
- No changes to the Ranger XML config files or YAML config structure
- No changes to the Maven build structure or profiles
