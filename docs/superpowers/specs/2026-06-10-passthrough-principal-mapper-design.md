# Passthrough Principal Mapper for Assessment Tool

**Date:** 2026-06-10
**Status:** Draft

## Background

When a customer runs the assessment tool for the first time against a Ranger export file,
they typically have no `principalMapping` config yet — that comes after they see which
policies are convertible. Without any mapping, `StaticPrincipalMapper` resolves every
principal to `Optional.empty()`, which causes every policy to produce zero LF operations
and land in "not convertible". This gives a misleading 100% not-convertible result before
the customer has done anything wrong.

The fix is to auto-detect the no-mapping case and substitute a `PassthroughPrincipalMapper`
that echoes Ranger principal names as synthetic placeholder strings. The customer gets a
structurally accurate convertibility result, with a prominent warning that real ARN mapping
is still needed.

## Goals

- First-run assessment without a config file produces meaningful convertibility percentages.
- Customer is clearly warned — in both console output and JSON — that principal mapping
  is not configured and must be added before production use.
- Zero CLI changes. Zero config-file changes. No new `PrincipalMapperType` value.

## Non-Goals

- This mapper is not serialisable to a config file; it is an internal assessment-only concern.
- It does not attempt to guess IAM ARNs (e.g. from account ID + username convention).

---

## Design

### 1. `PassthroughPrincipalMapper`

New class in `com.amazonaws.policyconverters.lakeformation`:

```java
public class PassthroughPrincipalMapper implements PrincipalMapper {
    @Override public Optional<String> resolveUser(String name)  { return Optional.of("ranger-user:"  + name); }
    @Override public Optional<String> resolveGroup(String name) { return Optional.of("ranger-group:" + name); }
    @Override public Optional<String> resolveRole(String name)  { return Optional.of("ranger-role:"  + name); }
}
```

The `ranger-user:alice` format makes placeholder identities visually distinct from real ARNs
in the report output and prevents any accidental confusion with actual IAM principals.

### 2. Activation Condition

In `AssessmentRunner.run()`, before constructing the `PrincipalMapper`, check whether
the config's `PrincipalMappingConfig` is effectively empty:

```java
private static boolean isDefaultEmptyMapping(PrincipalMappingConfig cfg) {
    return cfg != null
        && cfg.getType() == PrincipalMapperType.STATIC
        && cfg.getUserMappings().isEmpty()
        && cfg.getGroupMappings().isEmpty()
        && cfg.getRoleMappings().isEmpty()
        && cfg.getDelegates().isEmpty();  // getDelegates() is always non-null (returns emptyList when field is null)
}
```

The full modified block in `AssessmentRunner.run()` looks like:

```java
List<String> warnings = new ArrayList<>();
PrincipalMappingConfig principalMappingConfig = config.getPrincipalMapping();
PrincipalMapper principalMapper;
if (isDefaultEmptyMapping(principalMappingConfig)) {
    principalMapper = new PassthroughPrincipalMapper();
    String warning = "No principal mapping configured. Ranger usernames are passed through as-is "
            + "(e.g. \"ranger-user:alice\", \"ranger-group:analysts\"). Re-run with a config file "
            + "that includes a principalMapping section to produce accurate LF grant output.";
    warnings.add(warning);
    LOG.warn(warning);
} else {
    // existing IdentityCenter client setup ... then:
    principalMapper = PrincipalMapperFactory.create(principalMappingConfig, identityStoreClient, null);
}
```

And the `return` at the end of `run()` becomes:

```java
return new AssessmentResult(
        allPolicies.size(),
        counts[0], counts[1], counts[2],
        ops.size(),
        gapReport,
        source.sourceLabel(),
        assessedServices,
        warnings);
```

**Implementation notes for the null cases:**
- `AssessmentConfig.Builder` initialises `principalMapping` to
  `new PrincipalMappingConfig(null, null, null)` and normalises any null passed to
  `builder.principalMapping(null)` to the same default. So `config.getPrincipalMapping()`
  is **never null** at this call site — the `cfg != null` guard is purely defensive.
- `getDelegates()` returns `Collections.emptyList()` when the internal field is `null`
  (3-arg constructor chains to 6-arg with `delegates = null`). Calling `.isEmpty()` on
  the getter is always safe; do not access the private field directly.
- `PrincipalMapperFactory.create()` throws `IllegalArgumentException` on a null config,
  so the passthrough path must be decided before calling the factory — which is what this
  check does.
- If `cfg` is somehow null (bypassing the builder), the `cfg != null` guard makes the
  condition return `false`, causing `PrincipalMapperFactory.create(null, ...)` to throw.
  This is acceptable: any caller that bypasses the builder deserves the exception.

The condition is conservative: any non-empty mapping config (STATIC with entries,
IDENTITY_CENTER, COMPOSITE) skips the passthrough path entirely.

### 3. Warning Message

Single warning string added to `warnings` when passthrough activates:

```
No principal mapping configured. Ranger usernames are passed through as-is
(e.g. "ranger-user:alice", "ranger-group:analysts"). Re-run with a config file
that includes a principalMapping section to produce accurate LF grant output.
```

This string is stored **without** the `⚠` prefix — that is presentation-layer decoration
added by `AssessmentReporter` at display time. Storing the symbol in the string would cause
it to appear twice (once from `LOG.warn` and once from the reporter) and would embed a
Unicode symbol in the JSON output.

Also emitted via `LOG.warn(...)` before conversion starts.

### 4. `AssessmentResult` — `warnings` field

New field added as constructor position 9, following the existing `source`/`services`
backward-compat pattern:

```java
@JsonCreator
public AssessmentResult(
    @JsonProperty("totalPolicies")        int totalPolicies,
    // ... existing 5 fields ...
    @JsonProperty("gapReport")            GapReport gapReport,
    @JsonProperty("source")               String source,
    @JsonProperty("services")             List<AssessedService> services,
    @JsonProperty("warnings")             List<String> warnings)
```

Constructor null-guards: `warnings != null ? Collections.unmodifiableList(warnings) : Collections.emptyList()`.

`@JsonInclude(NON_EMPTY)` on `getWarnings()` — placed on the getter for consistency with
Jackson's property-level annotation model. This keeps the field absent from JSON when the
list is empty, preserving the existing report format for configured runs.

### 5. `AssessmentReporter` — console warning banner

If `result.getWarnings()` is non-empty, print each warning before the report header:

```
⚠  No principal mapping configured. Ranger usernames are passed through as-is
   (e.g. "ranger-user:alice", "ranger-group:analysts"). Re-run with a config file
   that includes a principalMapping section to produce accurate LF grant output.

=== Apache Ranger → Lake Formation Assessment ===
Source:       file:Ranger_Policies_20260602_001709.json
...
```

---

## Files Changed

| File | Change |
|---|---|
| `lakeformation/PassthroughPrincipalMapper.java` | **New** — implements `PrincipalMapper` |
| `assessment/AssessmentRunner.java` | Add `isDefaultEmptyMapping()` helper; activate passthrough; collect `warnings` |
| `assessment/AssessmentResult.java` | Add `warnings` field (position 9), `getWarnings()`, `@JsonInclude(NON_EMPTY)` on getter |
| `assessment/AssessmentReporter.java` | Print warning banner before report header |
| `assessment/AssessmentRunnerTest.java` | Add test: empty mapping → passthrough; non-empty mapping → no warning |
| `assessment/AssessmentReporterTest.java` | Add test: banner appears before header when warnings non-empty; no banner when empty. Update `buildResult(...)` helper to pass `List.of()` as 9th arg |
| `assessment/AssessmentResultTest.java` | **New** — JSON serialisation round-trip for `warnings` present/absent |
| `lakeformation/PassthroughPrincipalMapperTest.java` | **New** — unit tests for all three resolve methods |
| `README.md` | Document passthrough behaviour; show `warnings` in JSON report sample |

---

## Testing

- `PassthroughPrincipalMapperTest` — `resolveUser("alice")` returns `Optional.of("ranger-user:alice")`;
  `resolveGroup("analysts")` returns `Optional.of("ranger-group:analysts")`; `resolveRole("admin")`
  returns `Optional.of("ranger-role:admin")`. None return `Optional.empty()`.
- `AssessmentRunnerTest` — new test `run_withDefaultEmptyMapping_usesPassthroughAndWarns`:
  build config with no mapping (`AssessmentConfig.builder().consoleOnly(true).build()`),
  run with a simple lakeformation policy that has a user item, assert `result.getWarnings()`
  has size 1, `result.getTotalPolicies() == 1`, and
  `result.getFullyConvertible() + result.getPartiallyConvertible() > 0` (passthrough
  resolved the principal, producing at least one op). Do NOT assert `getFullyConvertible() > 0`
  alone — incidental gap entries on the test policy may shift it to partiallyConvertible.
- `AssessmentRunnerTest` — new test `run_withNonEmptyStaticMapping_noWarning`:
  use `minimalConfig()` (has `alice → arn:...` entry), assert `result.getWarnings()` is empty.
- Existing tests unaffected: all existing `AssessmentRunnerTest` stubs use `minimalConfig()`
  which has a user mapping entry, so they continue using the static mapper.
- `AssessmentReporterTest` — update `buildResult(...)` helper to pass `List.of()` for
  `warnings` (position 9) so existing tests compile. Add new test:
  `printConsoleReport_withWarnings_showsBannerBeforeHeader` — construct a result with one
  warning string, capture output, assert the `⚠` line appears before the `=== Apache Ranger`
  header line. Add complementary test that no `⚠` line appears when `warnings` is empty.
- `AssessmentResultTest` (new file) — verify `AssessmentResult` with non-empty `warnings`
  serialises the `"warnings"` JSON key; a result with empty warnings omits the key entirely.
  Also verify deserialization of old JSON (no `warnings` field) yields `getWarnings()`
  returning an empty list (backward-compat `@JsonCreator` null injection).
