# Composite Principal Mapper Design

## Goal

Allow operators to chain multiple `PrincipalMapper` implementations so that static user/group/role mappings take priority, with IAM Identity Center (IDC) as an automatic fallback for names not found in the static maps.

## Background

The sync pipeline resolves Ranger principal names (users, groups, roles) to IAM ARNs via a `PrincipalMapper`. Two implementations exist:

- `StaticPrincipalMapper` — reads a pre-configured `Map<String, String>` for each principal type. Fast, no I/O.
- `IdentityCenterPrincipalMapper` — queries the AWS IdentityStore API, with TTL-based caching.

Currently only one mapper can be active per deployment (`type: STATIC` or `type: IDENTITY_CENTER`). This design adds a `COMPOSITE` type that chains an ordered list of mappers, returning the first non-empty resolution.

## Architecture

### New enum value

`PrincipalMapperType` gains a third value: `COMPOSITE`.

### Config model change

`PrincipalMappingConfig` gains one new field:

```
delegates: List<PrincipalMappingConfig>   (JSON key: "delegates", default: empty list)
```

All existing fields (`type`, `userMappings`, `groupMappings`, `roleMappings`, `idcConfig`) are unchanged. A non-composite config is simply a `PrincipalMappingConfig` with no `delegates`.

**`@JsonCreator` constructor update:** The current 5-arg constructor carries `@JsonCreator`; the 3-arg is already a plain chain-up to it. The change is: (1) add a new 6-arg constructor with `@JsonProperty("delegates") List<PrincipalMappingConfig> delegates` as the final parameter and move `@JsonCreator` to it — there must be exactly one `@JsonCreator` on the class or Jackson throws `InvalidDefinitionException` at startup; (2) the existing 5-arg constructor becomes a plain backward-compat chain to the new 6-arg with `delegates = null`; (3) the 3-arg chains to the 5-arg today and requires no change — it will automatically flow through the updated 5-arg to the new 6-arg. `null` delegates coerces to an empty list in the constructor body.

**Constraints enforced at startup:**
- `COMPOSITE` requires `delegates` to be non-empty.
- Delegates must not themselves be `COMPOSITE` (no nesting — one level of chaining only).
- At most one delegate may be `IDENTITY_CENTER` (single IDC instance per deployment).
- An `IDENTITY_CENTER` delegate must have a valid `idcConfig`.
- Duplicate `STATIC` delegates are permitted (first-match wins for overlapping keys) but a single STATIC delegate covers all cases; multiple STATIC entries are not validated away, just silently redundant.

### Example config

```yaml
principalMapping:
  type: COMPOSITE
  delegates:
    - type: STATIC
      userMappings:
        "alice": "arn:aws:iam::123456789012:role/alice"
        "etl_user": "arn:aws:iam::123456789012:role/etl"
      groupMappings:
        "admins": "arn:aws:iam::123456789012:role/admins"
      roleMappings:
        "etl_role": "arn:aws:iam::123456789012:role/etl_role"
    - type: IDENTITY_CENTER
      idcConfig:
        identityStoreId: "d-1234567890"
        region: "us-east-1"
        accountId: "123456789012"
        cacheTtlMinutes: 60
```

### `CompositePrincipalMapper`

New class in `com.amazonaws.policyconverters.lakeformation`.

Holds an immutable `List<PrincipalMapper>` (resolved delegate instances, constructed with no metrics emitter). Each `resolve*` method iterates the list and returns the first non-empty result. Only after all delegates are exhausted does it record the unmapped-principal metric and return `Optional.empty()`.

```java
public class CompositePrincipalMapper implements PrincipalMapper {
    private final List<PrincipalMapper> delegates;
    private final MetricsEmitter metricsEmitter;  // nullable

    @Override
    public Optional<String> resolveUser(String name) {
        for (PrincipalMapper d : delegates) {
            Optional<String> result = d.resolveUser(name);
            if (result.isPresent()) return result;
        }
        recordUnmapped("user", name);
        return Optional.empty();
    }
    // identical pattern for resolveGroup, resolveRole
}
```

**Metric deduplication:** Delegate instances are constructed with `metricsEmitter = null` so they never emit the unmapped-principal metric on intermediate misses. `CompositePrincipalMapper` is the single authoritative emitter and fires exactly once per all-delegates-exhausted miss. This is enforced structurally: `PrincipalMapperFactory` always passes `null` for `MetricsEmitter` in the recursive delegate-construction calls — the only way to get a non-null emitter into a delegate would be to bypass the factory entirely, which no production code does.

**Roles and IDC:** `IdentityCenterPrincipalMapper.resolveRole()` always returns `Optional.empty()` (IDC has no role concept). Because delegates are constructed with `metricsEmitter = null`, it does so silently — no spurious metric fires for roles that were already resolved by an earlier static delegate.

### `PrincipalMapperFactory` changes

**Refactor the fall-through into an explicit `STATIC` branch, then add `COMPOSITE`, then throw for unknown types.** The existing factory has a single `if (IDENTITY_CENTER)` branch; `STATIC` (and everything else) is handled by falling through to `StaticPrincipalMapper.fromConfig()`. The change is: (1) extract the fall-through into an explicit `else if (STATIC)` branch calling `StaticPrincipalMapper.fromConfig()`; (2) add the `COMPOSITE` branch (see below); (3) replace the residual fall-through with `throw new IllegalArgumentException("Unknown mapper type: " + type)`. Steps 1–3 must land in the same commit — adding `COMPOSITE` to the enum before step 3 is safe because the fall-through still handles `STATIC` until the refactor is complete.

Adds a `COMPOSITE` branch that:
1. Validates the delegates list (non-empty, no nested composites, IDC uniqueness, IDC delegate has valid `idcConfig`).
2. Recursively calls `PrincipalMapperFactory.create(delegateConfig, identitystoreClient, null)` for each delegate — passing `null` for `MetricsEmitter` so delegates never emit intermediate-miss metrics.
3. Wraps the resulting list in `new CompositePrincipalMapper(delegates, metricsEmitter)`.

The `IdentitystoreClient` is passed through unchanged to the recursive call so any `IDENTITY_CENTER` delegate gets the same pre-constructed client instance.

## Error Handling

| Scenario | Behaviour |
|---|---|
| IDC unavailable mid-cycle | IDC delegate catches `SdkException`, returns `Optional.empty()`. Composite falls through to empty, records gap, skips that principal. Static delegate unaffected. |
| Stale IDC cache | IDC mapper's per-entry TTL handles expiry. No composite-level change needed. |
| Role principal, IDC-last delegate | IDC returns `Optional.empty()` silently (null emitter). If static also misses, composite records the gap and returns empty — same behaviour as today. |
| Empty static maps in a delegate | Always returns empty, passes through to next delegate. Legal and handled. |
| Unrecognized mapper type in factory | `IllegalArgumentException` at startup (fall-through default removed). |
| `COMPOSITE` config but empty delegates | `IllegalArgumentException` at startup with descriptive message. |
| Nested `COMPOSITE` delegate | `IllegalArgumentException` at startup with descriptive message. |

## Files Changed

| File | Change |
|---|---|
| `config/PrincipalMapperType.java` | Add `COMPOSITE` value |
| `config/PrincipalMappingConfig.java` | Extend `@JsonCreator` constructor to include `delegates` parameter; update backward-compat constructors to chain to new 6-arg form |
| `lakeformation/CompositePrincipalMapper.java` | New class |
| `lakeformation/PrincipalMapperFactory.java` | Remove fall-through default; add `COMPOSITE` branch with validation and recursive delegate construction |
| `conf/server-config.yaml` | Document composite config example |

## Tests

| Test class | What it covers |
|---|---|
| `CompositePrincipalMapperTest` | First-delegate hit returns immediately (no metric); second-delegate fallback returns (no metric); both miss → metric fires exactly once; static hit before IDC → no metric even though IDC would return empty for roles; role returns empty when IDC is last delegate (no spurious metric because null emitter) |
| `PrincipalMapperFactoryCompositeTest` | Factory builds correct ordered delegate chain; rejects nested composite; rejects empty delegates list; rejects IDC delegate missing idcConfig; unrecognized type throws (fall-through removed); delegates constructed with null emitter |
| `PrincipalMappingConfigCompositeTest` | JSON round-trip preserves all fields including delegates; `delegates` defaults to empty list when absent; 3-arg and 5-arg constructors chain correctly to 6-arg; existing non-composite configs deserialize unchanged (backward compat) |
| `CompositePrincipalMapperIntegrationTest` | `RangerToCedarConverter` wired with composite mapper: static hit → static ARN, no gap; static miss + IDC hit → IDC ARN, no gap; both miss → gap recorded, principal skipped |

## Backward Compatibility

No existing configs are affected. `delegates` defaults to an empty list. `PrincipalMapperFactory` only enters the composite branch when `type == COMPOSITE`. The removal of the factory fall-through is safe: the current factory has a single `if (IDENTITY_CENTER)` branch and falls through to `StaticPrincipalMapper.fromConfig()` for everything else. After adding `COMPOSITE` to the enum, that fall-through would silently produce an empty static mapper for a misconfigured composite config. The `COMPOSITE` enum addition and the factory fall-through removal must land in the same commit. Once done, the only values in the enum are `STATIC`, `IDENTITY_CENTER`, and `COMPOSITE`, all with explicit branches — the throw covers any future unknown value.
