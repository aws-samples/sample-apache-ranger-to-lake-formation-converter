# Principal Mapping

## `lakeformation/PrincipalMapper.java` (interface)

Maps Ranger users/groups/roles to AWS principal ARNs. Returns `Optional.empty()` when a principal cannot be resolved.

**Methods:** `resolveUser(String) → Optional<String>`, `resolveGroup(String) → Optional<String>`, `resolveRole(String) → Optional<String>`

---

## `lakeformation/PrincipalMapperFactory.java`

Creates the correct `PrincipalMapper` implementation from `PrincipalMappingConfig`.

`create(PrincipalMappingConfig, IdentitystoreClient, MetricsEmitter)`:
- `STATIC` → `StaticPrincipalMapper.fromConfig(config, metricsEmitter)`
- `IDENTITY_CENTER` → validates `idcConfig` fields non-null; creates `IdentityCenterPrincipalMapper`
- `COMPOSITE` → validates ≤1 IDENTITY_CENTER delegate and no nested COMPOSITE; builds delegate list with `null` MetricsEmitter (to prevent double-counting); wraps in `CompositePrincipalMapper`

---

## `lakeformation/StaticPrincipalMapper.java`

Static look-up table mapper. Loads from `PrincipalMappingConfig`, `.json` file, or `.properties` file.

**`.properties` file format:** `user.<name>=arn:...`, `group.<name>=arn:...`, `role.<name>=arn:...`

On miss: logs warning + calls `metricsEmitter.recordUnmappedPrincipal`.

---

## `lakeformation/IdentityCenterPrincipalMapper.java`

Resolves Ranger user/group names to Identity Center ARNs via `IdentityStore` API. Roles always return `Optional.empty()`.

**Cache:** `ConcurrentHashMap<String, CacheEntry>`. Key format: `"user:<name>"` or `"group:<name>"`. Cache entry: `(arn, expiresAt)`. TTL from `IdentityCenterConfig.cacheTtlMinutes`.

**User ARN format:** `arn:aws:identitystore::<accountId>:user/<userId>` (via `getUserId` with `UniqueAttribute(attributePath="userName")`)

**Group ARN format:** `arn:aws:identitystore::<accountId>:group/<groupId>` (via `getGroupId` with `UniqueAttribute(attributePath="displayName")`)

**On API failure:** `ResourceNotFoundException` or `SdkException` → returns empty, emits `metricsEmitter.recordUnmappedPrincipal`.

---

## `lakeformation/CompositePrincipalMapper.java`

Chains delegates in order, returns first non-empty result. Emits unmapped metric only after all delegates are exhausted. Delegates constructed with `null` MetricsEmitter by factory to avoid double-counting intermediate misses.

---

## `lakeformation/PassthroughPrincipalMapper.java`

Assessment/testing mapper. Builds placeholder ARNs with account `000000000000`:
- User → `arn:aws:iam::000000000000:user/ranger-user/<name>`
- Group → `arn:aws:iam::000000000000:group/ranger-group/<name>`
- Role → `arn:aws:iam::000000000000:role/ranger-role/<name>`

Used by `AssessmentMain` and offline conversion modes.
