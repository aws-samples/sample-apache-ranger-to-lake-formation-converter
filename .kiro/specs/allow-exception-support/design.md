# Design Document: Allow Exception Support

## Overview

This feature adds allow exception handling to the Ranger → Cedar → LakeFormation policy conversion pipeline. In Apache Ranger, `allowExceptions` items override allow rules — "everyone in group X gets access, except user Y." Currently the pipeline silently ignores allow exceptions, causing over-granting.

The implementation touches two components:

1. **RangerToCedarConverter** — generates `forbid` statements annotated with `@allowException("true")` for each (principal, action, resource) tuple in the Ranger `allowExceptions` list. This mirrors the existing `@denyException("true")` pattern used for deny exceptions.

2. **CedarToLFConverter** — extends `resolveEffectiveGrants()` to recognize `@allowException("true")` on forbid statements and suppress the corresponding GRANT operations, ensuring excepted principals do not receive unintended access.

Additionally, the legacy `PolicyConverter` class and its tests are removed since the Cedar-based pipeline now fully handles all four Ranger item types (allow, deny, denyException, allowException).

## Architecture

The existing two-stage pipeline architecture remains unchanged. The data flow gains a new annotation type:

```
Ranger Policy
  ├── policyItems (allow)         → Cedar permit
  ├── denyPolicyItems (deny)      → Cedar forbid
  ├── denyExceptions              → Cedar permit + @denyException("true")
  └── allowExceptions (NEW)       → Cedar forbid + @allowException("true")
         │
         ▼
   CedarPolicySet (intermediate)
         │
         ▼
   CedarToLFConverter.resolveEffectiveGrants()
     ├── permit + no forbid                          → GRANT
     ├── permit + forbid + denyException             → GRANT (deny-exception overrides deny)
     ├── permit + forbid(@allowException)            → NO GRANT (allow-exception suppresses)
     ├── permit + forbid(deny) + forbid(@allowExc)   → NO GRANT (either forbid suppresses)
     └── permit + forbid(deny) + no exceptions       → NO GRANT (deny wins)
```

### Resolution Truth Table

For a given (principal, action, resource) tuple:

| Allow | Deny | DenyException | AllowException | Effective |
|-------|------|---------------|----------------|-----------|
| ✓     | ✗    | ✗             | ✗              | GRANT     |
| ✓     | ✓    | ✗             | ✗              | NO GRANT  |
| ✓     | ✓    | ✓             | ✗              | GRANT     |
| ✓     | ✗    | ✗             | ✓              | NO GRANT  |
| ✓     | ✓    | ✓             | ✓              | NO GRANT  |
| ✓     | ✓    | ✗             | ✓              | NO GRANT  |

Key insight: an allow-exception suppresses the grant regardless of deny/deny-exception status. If an allow-exception exists for a tuple, the grant is always suppressed.

## Components and Interfaces

### 1. RangerToCedarConverter — Allow Exception Statement Generation

**Location:** `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java`

**Change:** Add a new block in `convertSinglePolicy()` after the existing deny-exception handling, processing `policy.getAllowExceptions()`:

```java
// Allow exception items → forbid statements with @allowException annotation
List<RangerPolicyItem> allowExceptionItems = policy.getAllowExceptions();
if (allowExceptionItems != null) {
    for (RangerPolicyItem item : allowExceptionItems) {
        statements.addAll(generateStatements(
                item, "forbid", policyId, adapter, resourceCombinations, "allowException"));
    }
}
```

**Change to `generateStatements()`:** Extend the annotation logic to handle `"allowException"` alongside `"denyException"`:

```java
if ("denyException".equals(extraAnnotation)) {
    sb.append("@denyException(\"true\")\n");
} else if ("allowException".equals(extraAnnotation)) {
    sb.append("@allowException(\"true\")\n");
}
```

**Generated Cedar output example:**
```cedar
@source("lakeformation:42")
@allowException("true")
forbid(
    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:user/userY",
    action == DataCatalog::Action::"SELECT",
    resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/mydb/mytable"
);
```

### 2. CedarToLFConverter — Allow Exception Resolution

**Location:** `src/main/java/com/amazonaws/policyconverters/cedar/CedarToLFConverter.java`

**Change 1:** Add a regex pattern for parsing the new annotation:

```java
private static final Pattern ALLOW_EXCEPTION_PATTERN = Pattern.compile(
        "@allowException\\(\"true\"\\)");
```

**Change 2:** Extend `ParsedStatement` to track allow-exception status:

```java
final boolean isAllowException;
```

**Change 3:** Update `parseOneStatement()` to detect the annotation:

```java
boolean isAllowException = ALLOW_EXCEPTION_PATTERN.matcher(raw).find();
```

**Change 4:** Update `resolveEffectiveGrants()` to collect allow-exception forbids into a separate set and use it during grant resolution:

```java
Set<ActionResourceKey> allowExceptionSet = new HashSet<>();

for (ParsedStatement stmt : statements) {
    ActionResourceKey key = new ActionResourceKey(stmt.action, stmt.resourceId);
    if ("forbid".equals(stmt.effect)) {
        if (stmt.isAllowException) {
            allowExceptionSet.add(key);
        }
        forbidSet.add(key);
    } else if ("permit".equals(stmt.effect) && stmt.isDenyException) {
        denyExceptionSet.add(key);
        permits.add(stmt);
    } else if ("permit".equals(stmt.effect)) {
        permits.add(stmt);
    }
}
```

**Updated grant resolution logic:**

```java
for (ParsedStatement permit : permits) {
    ActionResourceKey key = new ActionResourceKey(permit.action, permit.resourceId);

    // Allow-exception always suppresses the grant
    if (allowExceptionSet.contains(key)) {
        continue;
    }

    // Regular deny/deny-exception logic (unchanged)
    if (forbidSet.contains(key) && !denyExceptionSet.contains(key)) {
        continue;
    }

    // ... produce GRANT operation
}
```

The critical semantic: allow-exception check happens first and is absolute — it suppresses the grant regardless of deny-exception status. This matches the truth table above and Ranger's semantics where allow-exceptions carve out principals from the allow list.

### 3. Legacy PolicyConverter Removal

**Files to delete:**
- `src/main/java/com/amazonaws/policyconverters/ranger/PolicyConverter.java`
- `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java`
- `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterPropertyTest.java`

**Rationale:** The Cedar-based pipeline (`RangerToCedarConverter` → `CedarToLFConverter`) now handles all four Ranger item types. The legacy `PolicyConverter` only handled allow items and reported deny/deny-exception/allow-exception as gaps. It is fully superseded.

**Pre-deletion check:** Search for any imports or references to `PolicyConverter` in other source files and remove them.

## Data Models

### Modified: `CedarToLFConverter.ParsedStatement`

```java
static class ParsedStatement {
    final String effect;           // "permit" or "forbid"
    final String principal;        // IAM ARN
    final String action;           // Cedar action name
    final String resourceType;     // Cedar entity type
    final String resourceId;       // resource ARN
    final String sourcePolicyId;   // @source annotation value
    final boolean isDenyException; // @denyException("true") present
    final boolean isAllowException; // @allowException("true") present  ← NEW
    final String rowFilter;        // row filter expression
}
```

### Unchanged: `CedarPolicySet`

No changes needed. The `CedarPolicySet` is a transparent wrapper around Cedar text — the new `@allowException` annotation is just text that flows through to `CedarToLFConverter` for parsing.

### Unchanged: `RangerPolicy` (Ranger SDK)

The `getAllowExceptions()` method already exists on `RangerPolicy` in the Ranger SDK. No model changes needed.

### Unchanged: `LFPermissionOperation`, `LFResource`

The output model is unchanged — the feature only affects which operations are produced (suppressing grants for excepted principals), not the structure of the operations themselves.


## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Allow Exception Forbid Count

*For any* Ranger policy with allow exception items containing P mapped principals, A access types (mapping to C Cedar actions via the adapter), and R expanded resources, the RangerToCedarConverter SHALL produce exactly P × C × R forbid statements annotated with `@allowException("true")`, each carrying the correct `@source` annotation with the service-type-prefixed policy ID.

**Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5**

### Property 2: Unmapped Principals in Allow Exceptions Are Skipped

*For any* Ranger policy with allow exception items containing a mix of mapped and unmapped principals, the RangerToCedarConverter SHALL produce forbid statements only for the mapped principals. The count of `@allowException("true")` forbid statements SHALL equal (number of mapped principals) × C × R, not (total principals) × C × R.

**Validates: Requirements 1.6**

### Property 3: Four-Way Interaction Resolution

*For any* (principal, action, resource) tuple and any boolean combination of (hasAllow, hasDeny, hasDenyException, hasAllowException) where hasAllow is true, the CedarToLFConverter SHALL produce a GRANT if and only if:
- hasAllowException is false, AND
- (hasDeny is false OR hasDenyException is true)

In all other cases the GRANT SHALL be suppressed.

**Validates: Requirements 2.1, 2.2, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5**

### Property 4: End-to-End Pipeline Principal Partitioning

*For any* Ranger policy with allow items for a set of principals S and allow exception items for a subset E ⊆ S on the same (action, resource), the full pipeline (RangerToCedarConverter → CedarToLFConverter) SHALL produce GRANT operations exactly for the principals in S \ E (the non-excepted principals) and zero GRANTs for principals in E.

**Validates: Requirements 4.1, 4.2, 4.3**

### Property 5: Confluence (Statement Order Independence)

*For any* valid Ranger policy containing any combination of allow, deny, deny-exception, and allow-exception items, converting through RangerToCedarConverter and then shuffling the Cedar statements in any order before passing to CedarToLFConverter SHALL produce the same set of effective GRANT operations as the unshuffled conversion.

**Validates: Requirements 4.4**

## Error Handling

| Scenario | Behavior |
|----------|----------|
| `policy.getAllowExceptions()` returns null | Treated as empty list, no forbid statements generated |
| Allow exception item has no accesses | No forbid statements generated for that item (empty Cedar actions) |
| Allow exception item has unmapped principal | Principal skipped with warning log, other principals processed normally |
| Allow exception item has access type not in adapter mapping | Access type skipped (empty Cedar action set), no forbid for that action |
| `@allowException("true")` annotation on a permit statement | Ignored — only forbid statements are checked for allow-exception semantics |
| Both `@allowException` and `@denyException` on same statement | Not possible by construction — allow exceptions generate forbids, deny exceptions generate permits |
| Cedar schema validation rejects allow-exception forbid | Statement excluded, SCHEMA_VALIDATION_FAILURE gap recorded (same as existing behavior) |

## Testing Strategy

### Property-Based Tests (jqwik 1.7.4)

Each property test runs a minimum of 100 iterations. The project already uses jqwik for PBT.

**Property 1 & 2** — extend `RangerToCedarConverterPropertyTest.java`:
- Generate random principals (mix of mapped/unmapped), access types, and database resources
- Build Ranger policies with `allowExceptions` items
- Verify forbid count and annotation correctness
- Tag: `Feature: allow-exception-support, Property 1: Allow exception forbid count`
- Tag: `Feature: allow-exception-support, Property 2: Unmapped principals skipped`

**Property 3** — new test in `CedarToLFConverterPropertyTest.java` (or extend existing):
- Generate random (principal, action, resource) tuples
- Generate all 16 boolean combinations of (allow, deny, denyException, allowException) where allow=true (8 combinations)
- Build Cedar text with the appropriate statements
- Verify GRANT/no-GRANT matches the truth table formula
- Tag: `Feature: allow-exception-support, Property 3: Four-way interaction resolution`

**Property 4** — new test in a pipeline-level property test:
- Generate random Ranger policies with allow items for N principals and allow exceptions for a random subset
- Run through RangerToCedarConverter → CedarToLFConverter
- Verify GRANTs exist only for non-excepted principals
- Tag: `Feature: allow-exception-support, Property 4: End-to-end pipeline principal partitioning`

**Property 5** — new test in a pipeline-level property test:
- Generate random Ranger policies with all four item types
- Convert to Cedar, then shuffle the Cedar statements randomly
- Convert shuffled Cedar to LF operations
- Compare against unshuffled result
- Tag: `Feature: allow-exception-support, Property 5: Confluence`

### Unit Tests

Extend `RangerToCedarConverterTest.java` with:
- `allowExceptionProducesAnnotatedForbidStatements()` — single allow exception item produces forbid with `@allowException("true")`
- `allowExceptionWithMultiplePrincipals()` — multiple principals produce correct number of forbids
- `allowExceptionWithDenyAndDenyException()` — all four item types present, verify Cedar output structure

Extend or create `CedarToLFConverterTest.java` with:
- `allowExceptionSuppressesGrant()` — permit + @allowException forbid → no GRANT
- `allowExceptionDoesNotAffectOtherPrincipals()` — principal A gets GRANT, principal B (excepted) does not
- `allFourItemTypesWithAllowException()` — truth table row: allow + deny + denyException + allowException → no GRANT
- `allowAndDenyExceptionWithoutAllowException()` — truth table row: allow + deny + denyException → GRANT

### Legacy Removal Verification

After deleting `PolicyConverter.java`, `PolicyConverterTest.java`, and `PolicyConverterPropertyTest.java`:
- Run `mvn compile` to verify no compilation errors
- Run `mvn test` to verify all remaining tests pass
- Grep for `PolicyConverter` imports across the codebase to ensure no dangling references
