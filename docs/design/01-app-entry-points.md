# App Entry Points

## `app/SyncServiceMain.java`

**Purpose:** Lightweight CLI entry point for the standalone sync service. No HTTP server, no CloudWatch metrics, no reverse-sync. Loads config, wires components, blocks on `CountDownLatch` until SIGTERM.

### Startup sequence

1. `loadAndValidateConfig()` → `ConfigLoader.load()` → `ConfigValidator.validate()` — fails fast on any error
2. `verifyCedarNativeLibrary()` — instantiates `BasicAuthorizationEngine` to trigger JNI; exits if `.so` is missing
3. Build `GlueClient`, `LakeFormationClient` (SDK), optionally `IdentitystoreClient`
4. Build `PrincipalMapper`, `CatalogResolver`, `GapReporter`, `CedarSchemaProvider`, `AwsContext`
5. Iterate `config.getRangerServices()` → `ConversionServerMain.createRangerService(cfg)` → build `adapterRegistry`; fallback to single `RangerServiceAdapter("lakeformation")` if list absent
6. Build `RangerToCedarConverter`, `CedarToLFConverter`
7. Select `LakeFormationClient` impl: `DryRunLakeFormationClient` (env `DRY_RUN_ENABLED=true`) or real `LakeFormationClient`
8. Open `deadLetterWriter` → `DeadLetterLogger`
9. Optionally build `S3AccessGrantsClient`
10. Build `CheckpointStore` (default path `./checkpoint/sync-checkpoint.json`)
11. Construct `SyncService` (multi-service ctor)
12. Register shutdown hook: `syncService.stop()`, close writers/clients, countdown latch
13. `service.init()` on each `BaseRangerService`
14. `syncService.start(config)`
15. `KEEP_ALIVE_LATCH.await()`

### Environment variable overrides (via `ConfigLoader`)

`RANGER_ADMIN_URL`, `RANGER_USERNAME`, `RANGER_PASSWORD`, `RANGER_KERBEROS_KEYTAB`, `RANGER_KERBEROS_PRINCIPAL`, `RANGER_MAX_RETRIES`, `RANGER_RETRY_BACKOFF_MS`, `AWS_REGION`, `AWS_CATALOG_ID`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `AWS_ROLE_ARN`, `POLICY_REFRESH_INTERVAL_MS`, `MAX_LF_RETRIES`, `LF_RETRY_BACKOFF_MS`, `DEAD_LETTER_LOG_PATH`, `CHECKPOINT_PATH`.

Additional (read directly, not via ConfigLoader): `DRY_RUN_ENABLED`, `DRY_RUN_OUTPUT_DIR`.

### Known gap vs ConversionServerMain

`SyncServiceMain` does **not** wire:
- `ReverseSyncService` — the reconciliation component
- `WildcardRefreshScheduler`
- `MetricsEmitter` / CloudWatch
- `StatusEndpoint` (HTTP)
- Tag sync

The `ReverseSyncService` wiring is the target of Bug #1 fix. See [02-sync-pipeline.md](02-sync-pipeline.md).

---

## `app/ConversionServerMain.java`

**Purpose:** Production "Conversion Server" entry point. Adds `MetricsEmitter`, `StatusEndpoint`, `WildcardRefreshScheduler`, `ServerLifecycle`, `ReverseSyncService`, and tag-sync on top of the core pipeline.

### Startup sequence

1. `loadConfig()` via `ServerConfigLoader.load()` — extracts `SyncConfig`, `ServerConfig`, `ReverseSyncConfig` from a single YAML
2. `setLogLevel()` — programs Logback from `serverConfig.getLogLevel()`
3. Build AWS clients including `CloudWatchClient`; build `MetricsEmitter`
4. Conditionally build `IdentitystoreClient` (also handles `COMPOSITE` mappers)
5. Build core components (same as `SyncServiceMain`)
6. Multi-service or single-service pipeline branching (see `createRangerService()`)
7. Optionally build `ReverseSyncService` if `reverseSyncConfig.isEnabled()`
8. Build `SyncCycleExecutor` lambda — after each forward sync, optionally triggers `reverseSyncService.execute()`
9. Wire `MetricsEmitter` into all adapters and `AccessTypeMapper`
10. Create shared `ReentrantLock cycleLock`
11. Build `ServerLifecycle`, `WildcardRefreshScheduler` (started if `wildcardRefreshIntervalSeconds > 0`), `StatusEndpoint`
12. Register SIGTERM shutdown hook
13. Wire tag sync if `tagSync.enabled`
14. `syncService.start(syncConfig)`
15. `serverLifecycle.run()` — blocks until shutdown

### Key static methods

- `createRangerService(RangerServiceConfig) → BaseRangerService`: dispatches on `serviceType` string to `LakeFormationRangerService`, `HiveRangerService`, `PrestoRangerService`, `TrinoRangerService`, `EmrfsRangerService`, `EmrSparkRangerService`
- `fetchPoliciesFromRangerAdmin(url, user, password[, serviceInstanceName]) → ServicePolicies`: direct REST `GET /service/public/v2/api/service/{name}/policy`, Basic auth, 10s timeouts, filters disabled policies, wraps result with `policyVersion=System.currentTimeMillis()`
- `buildCredentialsProvider(AwsConfig) → AwsCredentialsProvider`: static creds → STS assume-role → default chain

### HTTP endpoint

`GET /status` (on `serverConfig.statusPort`, default 18080):
```json
{"lastCompletedCycle": 1234567890123, "lastCompletedWildcardRefreshCycle": 0, "state": "running"}
```

### ReverseSyncService scheduling

There is **no periodic scheduler** for reverse-sync. It runs **in-band** after every forward-sync cycle inside the `SyncCycleExecutor` lambda. The `periodicIntervalMs` field in `ReverseSyncConfig` exists but is currently unused for scheduling.

### Dry-run activation

Env `DRY_RUN_ENABLED=true` → swaps real `LakeFormationClient` for `DryRunLakeFormationClient` (output dir from `DRY_RUN_OUTPUT_DIR` or `./dry-run-output`). `reverseSyncConfig.isDryRun()=true` also uses `DryRunLakeFormationClient` for corrective operations.
