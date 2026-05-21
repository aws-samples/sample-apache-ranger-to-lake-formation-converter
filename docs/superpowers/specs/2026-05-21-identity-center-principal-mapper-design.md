# Identity Center Principal Mapper — Design Spec

**Date:** 2026-05-21
**Status:** Approved

---

## Problem

The project's `PrincipalMapper` class maps Ranger users/groups/roles to AWS principal ARNs using a static config file maintained by hand. When the identity source is an enterprise directory synced into AWS IAM Identity Center (IDC), operators must duplicate every user and group into the mapping file and keep it in sync manually. This is operationally fragile and does not scale.

## Goal

Add a second principal mapper implementation that resolves Ranger directory identities (users and groups) to IDC principals at runtime by querying the AWS IdentityStore API. The resulting principal ARNs are used when issuing Lake Formation grants. The choice of mapper is controlled by a single config field, with the static mapper remaining the default for backward compatibility.

---

## Build Changes

Add `software.amazon.awssdk:identitystore` to `pom.xml`. Version is managed by the existing AWS SDK BOM (`aws.sdk.version=2.20.162`):

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>identitystore</artifactId>
</dependency>
```

---

## Architecture

### Interface Extraction

`PrincipalMapper` becomes an interface:

```java
public interface PrincipalMapper {
    Optional<String> resolveUser(String rangerUser);
    Optional<String> resolveGroup(String rangerGroup);
    Optional<String> resolveRole(String rangerRole);
}
```

All downstream consumers (`RangerToCedarConverter`, `PolicyConverter`, `LakeFormationClient`) already accept a `PrincipalMapper` reference and call these three methods — no changes needed there.

### Implementations

| Class | Description |
|---|---|
| `StaticPrincipalMapper` | Renamed current `PrincipalMapper` class. Resolves via static config maps. Receives `MetricsEmitter` via constructor. |
| `IdentityCenterPrincipalMapper` | New. Queries AWS IdentityStore API at runtime. Receives `MetricsEmitter` via constructor. |

Both implementations:
- Return `Optional.empty()` when a principal cannot be resolved.
- Emit a WARN log.
- Call `metricsEmitter.recordUnmappedPrincipal(principalType)` so CloudWatch alarms can be set.

`MetricsEmitter` is nullable in both implementations and guarded before use, following the `AccessTypeMapper` pattern.

### Factory

`PrincipalMapperFactory.create(config, identityStoreClient, metricsEmitter)` reads `config.getType()`, constructs the appropriate implementation, and passes `metricsEmitter` to both. Validation of required IDC config fields happens here at startup.

---

## Configuration

### Schema

```yaml
# Option A: static mapper (default — existing files need no change)
principalMapping:
  userMappings:
    "ranger_alice": "arn:aws:iam::123456789012:user/alice"
  groupMappings:
    "data_engineers": "arn:aws:iam::123456789012:role/DataEngineersRole"

# Option B: Identity Center mapper
principalMapping:
  type: IDENTITY_CENTER
  idcConfig:
    identityStoreId: "d-1234567890"   # required
    region: "us-east-1"               # required
    accountId: "123456789012"         # required — used in principal ARN construction
    cacheTtlMinutes: 60               # optional, default 60
```

### New Config Types

**`PrincipalMapperType`** (enum): `STATIC`, `IDENTITY_CENTER`. Absence of `type` coerces to `STATIC`.

**`IdentityCenterConfig`** (POJO):
- `identityStoreId` — Identity Store ID (e.g. `d-xxxxxxxxxx`). Required for IDC mapper.
- `region` — AWS region for the IdentityStore API endpoint. Required.
- `accountId` — AWS account ID that owns the Identity Store. Required. Used when constructing principal ARNs.
- `cacheTtlMinutes` — Cache TTL in minutes. Default 60.

**`PrincipalMappingConfig`** (expanded): gains `type` and `idcConfig`. The existing three-arg constructor is kept as a chained backward-compat constructor that delegates to the new canonical `@JsonCreator` with `type=null` (coerced to `STATIC`) and `idcConfig=null`:

```java
// Backward-compat constructor — existing callers and old YAML unaffected
public PrincipalMappingConfig(
        Map<String, String> userMappings,
        Map<String, String> groupMappings,
        Map<String, String> roleMappings) {
    this(userMappings, groupMappings, roleMappings, null, null);
}

// Canonical @JsonCreator constructor
@JsonCreator
public PrincipalMappingConfig(
        @JsonProperty("userMappings")  Map<String, String> userMappings,
        @JsonProperty("groupMappings") Map<String, String> groupMappings,
        @JsonProperty("roleMappings")  Map<String, String> roleMappings,
        @JsonProperty("type")          PrincipalMapperType type,
        @JsonProperty("idcConfig")     IdentityCenterConfig idcConfig) {
    // ... type defaults to STATIC when null
    this.type = type != null ? type : PrincipalMapperType.STATIC;
    this.idcConfig = idcConfig;
    // ... maps as before
}
```

`PrincipalMapperFactory` is responsible for validating that `idcConfig != null` and its required fields are set when `type == IDENTITY_CENTER` — no changes to `ConfigValidator` required.

---

## `StaticPrincipalMapper` Changes

`StaticPrincipalMapper` gains a `MetricsEmitter metricsEmitter` constructor parameter. The `fromConfig` factory is updated to accept it:

```java
public static StaticPrincipalMapper fromConfig(PrincipalMappingConfig config, MetricsEmitter metricsEmitter)
```

The `fromFile(String filePath)` test helper is preserved with a `null` emitter for backward compatibility with existing tests.

In the private `resolve()` method, the existing WARN log is kept and a metric call is added:

```java
if (arn == null) {
    LOG.warn("No mapping found for {} principal '{}', skipping", principalType, name);
    if (metricsEmitter != null) {
        metricsEmitter.recordUnmappedPrincipal(principalType);
    }
    return Optional.empty();
}
```

---

## `IdentityCenterPrincipalMapper` Design

### Lookup Flow

The IdentityStore API's `Filters` parameter on `ListUsers`/`ListGroups` is deprecated. The correct API surface is `GetUserId` and `GetGroupId`, which use `UniqueAttribute` for lookup and throw `ResourceNotFoundException` when the principal is not found.

**`resolveUser(String rangerUser)`:**
1. Null input → WARN + `Optional.empty()` (no API call, no metric).
2. Cache hit (not expired) → return cached ARN.
3. Call `identityStoreClient.getUserId(GetUserIdRequest.builder().identityStoreId(identityStoreId).alternateIdentifier(AlternateIdentifier.builder().uniqueAttribute(UniqueAttribute.builder().attributePath("userName").attributeValue(Document.fromString(rangerUser)).build()).build()).build())` — note `attributeValue` takes `Document.fromString(...)` not a raw `String` (AWS SDK v2 models this field as `Document`):
   - Success → ARN = `"arn:aws:identitystore::" + accountId + ":user/" + response.userId()`, cache with TTL, return.
   - `ResourceNotFoundException` → WARN + metric + `Optional.empty()`.
   - Any other `IdentityStoreException` or `SdkClientException` → WARN + metric + `Optional.empty()`.

**`resolveGroup(String rangerGroup)`:** Same pattern using `GetGroupId` with `attributePath="displayName"`, ARN = `"arn:aws:identitystore::" + accountId + ":group/" + response.groupId()`.

**`resolveRole(String rangerRole)`:** Always `Optional.empty()` + WARN + `metricsEmitter.recordUnmappedPrincipal("role")`. Roles have no IDC concept, but the metric is still emitted so operators receive an alarm signal rather than silent grant omission.

### ARN Format

ARNs are constructed as:
```
arn:aws:identitystore::<ACCOUNT_ID>:user/<UUID>
arn:aws:identitystore::<ACCOUNT_ID>:group/<UUID>
```
`accountId` comes from `IdentityCenterConfig`. If Lake Formation turns out to accept empty-account-ID ARNs (as occasionally observed in LF list-permissions output), this field can be made optional and defaulted to empty — but requiring it explicitly is safer until confirmed.

### Cache

- `ConcurrentHashMap<String, CacheEntry>` keyed by `"user:<name>"` / `"group:<name>"`.
- `CacheEntry`: `String arn` + `Instant expiresAt` (set to `Instant.now().plus(cacheTtlMs)`).
- Read pattern: check map, if entry exists and not expired return it. Otherwise call API, then `put` the new entry. Two concurrent threads may both see an expired entry and both issue API calls — the last writer wins with the same value, which is a benign duplicate call. `ConcurrentHashMap.compute()` is explicitly avoided because it holds a bin lock for the duration of the blocking SDK call, causing thread starvation under concurrency.
- Default TTL: 60 minutes (configurable via `idcConfig.cacheTtlMinutes`).

---

## Metrics

New method on `MetricsEmitter`:

```java
public void recordUnmappedPrincipal(String principalType)
// Metric: UnmappedPrincipal, Unit: COUNT, Dimension: PrincipalType=user|group
```

Pattern is identical to the existing `recordUnmappedAccessType(String accessType)`. This metric is emitted by both `StaticPrincipalMapper` (on map miss) and `IdentityCenterPrincipalMapper` (on no IDC match or API error), enabling a single CloudWatch alarm covering both mapper types.

---

## Wiring (`SyncServiceMain`)

```java
// Build IdentityStoreClient only when needed
IdentityStoreClient identityStoreClient = null;
if (config.getPrincipalMapping().getType() == PrincipalMapperType.IDENTITY_CENTER) {
    identityStoreClient = IdentityStoreClient.builder()
        .region(Region.of(config.getPrincipalMapping().getIdcConfig().getRegion()))
        .credentialsProvider(existingCredentialsProvider)
        .build();
}

PrincipalMapper principalMapper = PrincipalMapperFactory.create(
    config.getPrincipalMapping(), identityStoreClient, metricsEmitter);
```

The `PrincipalMapper` reference is then passed into `RangerToCedarConverter` as before.

---

## Testing Strategy

| Test class | Coverage |
|---|---|
| `StaticPrincipalMapperTest` | All existing cases + unmapped principal emits metric (Mockito mock for `MetricsEmitter`) |
| `IdentityCenterPrincipalMapperTest` | Happy path user/group, `ResourceNotFoundException` → metric, other SDK exception → metric, cache hit (no second API call), cache expiry (fresh API call), null input (no API call), role always empty (no metric) |
| `PrincipalMapperFactoryTest` | STATIC type, IDENTITY_CENTER valid, null `idcConfig`, blank `identityStoreId`, blank `region`, blank `accountId`, null config |
| `MetricsEmitterTest` | `recordUnmappedPrincipal` publishes correct metric name, unit, and `PrincipalType` dimension |

Mocks: `IdentityStoreClient` and `MetricsEmitter` via Mockito.

---

## Backward Compatibility

- Existing YAML config files with no `type` field continue to work — `type` defaults to `STATIC` in the constructor.
- Existing `fromFile(String)` test helper on `StaticPrincipalMapper` is preserved with a null emitter.
- No changes to `RangerToCedarConverter`, `PolicyConverter`, or `LakeFormationClient`.
