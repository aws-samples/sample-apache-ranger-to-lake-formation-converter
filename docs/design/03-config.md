# Configuration Classes

## `config/SyncConfig.java`

Top-level config for the sync pipeline. Immutable; Jackson `@JsonCreator`. Composed by `ServerConfigLoader.CompositeConfig`.

| JSON key | Type | Default |
|---|---|---|
| `rangerConfig` | `RangerConnectionConfig` | null (required) |
| `awsConfig` | `AwsConfig` | null (required) |
| `principalMapping` | `PrincipalMappingConfig` | null |
| `policyRefreshIntervalMs` | `long` | 30000 |
| `maxLfRetries` | `int` | 5 |
| `lfRetryBackoffMs` | `long` | 2000 |
| `deadLetterLogPath` | `String` | null → `"dead-letter.log"` |
| `checkpointPath` | `String` | null → `"./checkpoint/sync-checkpoint.json"` |
| `wildcardRefreshIntervalSeconds` | `int` | 0 (disabled) |
| `rangerServices` | `List<RangerServiceConfig>` | null → single `lakeformation` service |
| `tagSync` | `TagSyncConfig` | `TagSyncConfig(false, null, 0)` |
| `s3AccessGrants` | `S3AccessGrantsConfig` | null |

**Note on constructor chain:** Uses a chain of backward-compat constructors (3-arg → 7-arg → 9-arg → 10-arg → 11-arg → 12-arg `@JsonCreator`). When adding new fields, add a new `@JsonCreator` at the longest arity and chain the previous one to it with defaults.

---

## `config/ReverseSyncConfig.java`

Config for the reverse-sync feature. Parsed from the `reverseSync:` section of the server YAML.

| JSON key | Type | Default |
|---|---|---|
| `enabled` | `boolean` | `false` |
| `catalogId` | `String` | null (falls back to `awsConfig.catalogId`) |
| `reportOnly` | `boolean` | `false` |
| `dryRun` | `boolean` | `false` |
| `filter` | `PermissionFilter` | null |
| `exclusionFilter` | `PermissionFilter` | null (alias for `filter`; `filter` takes precedence) |
| `periodicIntervalMs` | `long` | 0 (currently unused for scheduling) |

---

## `config/ServerConfig.java`

Config for the server process. Parsed from the `server:` section.

| JSON key | Type | Default |
|---|---|---|
| `shutdownTimeoutSeconds` | `int` | 30 |
| `logLevel` | `String` | `"INFO"` |
| `metricsNamespace` | `String` | `"RangerLFSync"` |
| `statusPort` | `int` | 18080 |

Env overrides: `SERVER_SHUTDOWN_TIMEOUT_SECONDS`, `SERVER_LOG_LEVEL`, `SERVER_METRICS_NAMESPACE`.

---

## `config/AwsConfig.java`

| JSON key | Type | Notes |
|---|---|---|
| `region` | `String` | required |
| `catalogId` | `String` | required (Glue catalog = AWS account ID) |
| `accessKey` | `String` | optional; masked in logs |
| `secretKey` | `String` | optional; masked in logs |
| `roleArn` | `String` | optional; triggers STS AssumeRole |

Credential precedence (in both entry points): static + roleArn → STS AssumeRole with static base; static only → `StaticCredentialsProvider`; roleArn only → STS AssumeRole with default chain; neither → `DefaultCredentialsProvider`.

---

## `config/RetryConfig.java`

| Field | Type | Default |
|---|---|---|
| `maxRetries` | `int` | 3 |
| `initialBackoffMs` | `long` | 1000 |
| `backoffMultiplier` | `double` | 2.0 |
| `maxBackoffMs` | `long` | 30000 |

Both entry points construct with `maxLfRetries` from `SyncConfig`, `lfRetryBackoffMs`, multiplier 2.0, max 30000.

---

## `config/RangerConnectionConfig.java`

| JSON key | Type | Default |
|---|---|---|
| `rangerAdminUrl` | `String` | null (required) |
| `username` | `String` | null |
| `password` | `String` | null |
| `kerberosKeytab` | `String` | null |
| `kerberosPrincipal` | `String` | null |
| `maxRetries` | `int` | 3 |
| `retryBackoffMs` | `long` | 1000 |

`ConfigValidator` requires either `username+password` OR `kerberosKeytab+kerberosPrincipal`.

---

## `config/RangerServiceConfig.java`

One entry in the `rangerServices` list.

| JSON key | Type | Notes |
|---|---|---|
| `serviceType` | `String` | One of: `lakeformation`, `hive`, `presto`, `trino`, `amazon-emr-emrfs`, `amazon-emr-spark` |
| `serviceInstanceName` | `String` | Ranger service instance name |
| `serviceDefPath` | `String` | Optional path to service definition JSON |
| `gdcCatalogName` | `String` | Required for `presto` and `trino` |

**Note:** `ConfigValidator.ALLOWED_SERVICE_TYPES` does NOT include `amazon-emr-spark` — this is a known discrepancy. `createRangerService()` does accept it.

---

## `config/PrincipalMappingConfig.java`

| JSON key | Type | Default |
|---|---|---|
| `userMappings` | `Map<String,String>` | empty map |
| `groupMappings` | `Map<String,String>` | empty map |
| `roleMappings` | `Map<String,String>` | empty map |
| `type` | `PrincipalMapperType` | `STATIC` |
| `idcConfig` | `IdentityCenterConfig` | null |
| `delegates` | `List<PrincipalMappingConfig>` | null (treated as empty) |

`@JsonInclude(NON_EMPTY)` on `getDelegates()` — omitted from JSON when empty.

---

## `config/IdentityCenterConfig.java`

| JSON key | Type | Default |
|---|---|---|
| `identityStoreId` | `String` | null (required for IDC mapper) |
| `region` | `String` | null (required for IDC mapper) |
| `accountId` | `String` | null |
| `cacheTtlMinutes` | `int` | 60 |

---

## `config/PrincipalMapperType.java`

Enum: `STATIC`, `IDENTITY_CENTER`, `COMPOSITE`.

---

## `config/TagSyncConfig.java`

| JSON key | Type | Default |
|---|---|---|
| `enabled` | `boolean` | `false` |
| `tagServiceName` | `String` | null (required when enabled) |
| `tagSyncIntervalMs` | `long` | 0 → falls back to `policyRefreshIntervalMs` |

---

## `config/S3AccessGrantsConfig.java`

Java record.

| JSON key | Type |
|---|---|
| `instanceArn` | `String` |
| `accountId` | `String` |

---

## `config/ConfigLoader.java`

Loads `SyncConfig` from YAML (`YAMLFactory`) or `.properties` files. Applies environment variable overrides. Supports injectable `EnvironmentProvider` for testing.

**env vars recognized (static constants):** `RANGER_ADMIN_URL`, `RANGER_USERNAME`, `RANGER_PASSWORD`, `RANGER_KERBEROS_KEYTAB`, `RANGER_KERBEROS_PRINCIPAL`, `RANGER_MAX_RETRIES`, `RANGER_RETRY_BACKOFF_MS`, `AWS_REGION`, `AWS_CATALOG_ID`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `AWS_ROLE_ARN`, `POLICY_REFRESH_INTERVAL_MS`, `MAX_LF_RETRIES`, `LF_RETRY_BACKOFF_MS`, `DEAD_LETTER_LOG_PATH`, `CHECKPOINT_PATH`.

**Note:** `applyEnvironmentOverrides()` does NOT propagate `tagSync` or `s3AccessGrants` — those come from file only.

---

## `config/ServerConfigLoader.java`

Parses a single YAML file containing both the `SyncConfig` payload and the `server:` / `reverseSync:` sections. Strips those sections into a temp file before delegating to `ConfigLoader`.

**Inner class `CompositeConfig`:** holds `SyncConfig`, `ServerConfig`, nullable `ReverseSyncConfig`.

**Key method:** `defaultCatalogId(ReverseSyncConfig, awsCatalogId)` — fills in missing `catalogId` from the AWS config.

---

## `config/ConfigValidator.java`

Eagerly collects all validation errors across `SyncConfig` before any operations begin. Returns all errors at once.

**ALLOWED_SERVICE_TYPES** (static constant): `lakeformation`, `hive`, `presto`, `trino`, `amazon-emr-emrfs`. **Missing: `amazon-emr-spark`** — a known gap.

**Sub-validators:** `validateRangerConfig` (URL format, auth), `validateAwsConfig` (region, catalogId), `validateRangerServices` (non-blank name, known type, no duplicates, `gdcCatalogName` required for presto/trino), `validateTagSyncConfig` (tagServiceName required when enabled).
