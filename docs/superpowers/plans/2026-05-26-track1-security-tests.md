# Track 1: Security-Critical Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 8 missing security-critical tests (5 unit/property, 3 integration) that verify deny semantics, principal mapping failures, grantable flag propagation, and wildcard revocation.

**Architecture:** All new tests live in existing test source trees; they follow the patterns already established in `CedarToLFConverterTest`, `PolicyConverterTest`, and `DryRunPipelineIT`. No new production code is required for Track 1 — these tests exercise the existing pipeline as-is (and some are expected to reveal real gaps).

**Tech Stack:** JUnit 5, jqwik 1.7.4, Mockito 4.11, Java 17 text blocks. Integration tests require a Dockerized Ranger Admin on `http://localhost:6080` (same as existing ITs).

---

## File Map

| File | Create / Modify |
|------|-----------------|
| `src/test/java/com/amazonaws/policyconverters/cedar/CedarToLFConverterTest.java` | Modify — add 2 tests |
| `src/test/java/com/amazonaws/policyconverters/cedar/CedarToLFConverterCrossPolicyPropertyTest.java` | Create |
| `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java` | Modify — add 1 test |
| `src/test/java/com/amazonaws/policyconverters/PipelineUnmappedPrincipalTest.java` | Create |
| `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java` | Modify — add deny-only test (Task 3) AND grantable propagation tests (Task 5) |
| `src/test/java/com/amazonaws/policyconverters/RowFilterPassThroughTest.java` | Create |
| `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/CrossPolicyDenyInteractionIT.java` | Create |
| `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/WildcardRevocationOnTableRemovalIT.java` | Create |
| `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/GrantablePermissionPropagationIT.java` | Create |

---

## Task 1: Cross-Policy Forbid Suppression and Deny-Exception Specificity (Unit)

These two tests extend `CedarToLFConverterTest`. They verify two critical Cedar semantics:
1. A `forbid` in policy A suppresses a `permit` in policy B for the same triple — the converter works on a `CedarPolicySet` (all policies together), so forbids from any source suppress all matching permits.
2. A `@denyException` for `(action1, resource1)` does NOT accidentally un-suppress a permit for `(action2, resource2)` where there is no deny-exception.

**Files:**
- Modify: `src/test/java/com/amazonaws/policyconverters/cedar/CedarToLFConverterTest.java`

- [ ] **Step 1: Write the two failing tests**

Open `CedarToLFConverterTest.java` and append these two `@Test` methods before the closing `}`:

```java
@Test
void forbidFromOnePolicySuppressesPermitFromAnotherPolicy() throws Exception {
    // Policy A: forbid analyst SELECT on db.orders
    // Policy B: permit analyst SELECT on db.orders
    // Result: zero grants — the forbid from policy A suppresses policy B's permit
    String cedarText = """
            @source("policy-A")
            forbid(
                principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                action == DataCatalog::Action::"SELECT",
                resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/testdb/orders"
            );
            @source("policy-B")
            permit(
                principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                action == DataCatalog::Action::"SELECT",
                resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/testdb/orders"
            );
            """;
    CedarPolicySet policySet = CedarPolicySet.fromCedarString(cedarText);
    List<LFPermissionOperation> ops = converter.convert(policySet);
    assertEquals(0, ops.size(),
            "A forbid from any policy must suppress a permit for the same (principal, action, resource)");
}

@Test
void denyExceptionForOneActionDoesNotRestorePermitForDifferentAction() throws Exception {
    // forbid: analyst SELECT on db.orders
    // permit with @denyException: analyst INSERT on db.orders  (different action — no exception for SELECT)
    // permit: analyst SELECT on db.orders
    // Result: INSERT is granted (has deny-exception), SELECT is NOT granted (forbid, no exception)
    String cedarText = """
            @source("policy-forbid")
            forbid(
                principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                action == DataCatalog::Action::"SELECT",
                resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/testdb/orders"
            );
            @source("policy-exception")
            @denyException("true")
            permit(
                principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                action == DataCatalog::Action::"INSERT",
                resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/testdb/orders"
            );
            @source("policy-select")
            permit(
                principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                action == DataCatalog::Action::"SELECT",
                resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/testdb/orders"
            );
            """;
    CedarPolicySet policySet = CedarPolicySet.fromCedarString(cedarText);
    List<LFPermissionOperation> ops = converter.convert(policySet);

    // INSERT must be granted (deny-exception present for INSERT)
    long insertGrants = ops.stream()
            .filter(op -> op.getOperationType() == OperationType.GRANT)
            .filter(op -> op.getPermissions().contains(LFPermission.INSERT))
            .count();
    assertEquals(1, insertGrants, "INSERT should be granted — it has a deny-exception");

    // SELECT must NOT be granted (forbid present, no deny-exception for SELECT)
    long selectGrants = ops.stream()
            .filter(op -> op.getOperationType() == OperationType.GRANT)
            .filter(op -> op.getPermissions().contains(LFPermission.SELECT))
            .count();
    assertEquals(0, selectGrants,
            "SELECT must NOT be granted — a deny-exception for a different action must not restore it");
}
```

- [ ] **Step 2: Run the tests to confirm they compile and check current behavior**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=CedarToLFConverterTest#forbidFromOnePolicySuppressesPermitFromAnotherPolicy+denyExceptionForOneActionDoesNotRestorePermitForDifferentAction -q 2>&1 | tail -20
```

Expected: both PASS (the converter already processes all policies in a single `CedarPolicySet`, so cross-policy suppression should work). If either FAILS, that is a real security bug — do not skip, investigate the converter logic before proceeding.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/cedar/CedarToLFConverterTest.java
git commit -m "test: add cross-policy forbid suppression and deny-exception specificity tests"
```

---

## Task 2: Cross-Policy Forbid Property Test (Property-Based)

This property test verifies that for any `CedarPolicySet` containing at least one forbid for a `(principal, action, resource)` triple with no matching deny-exception, the output contains zero grants for that exact triple.

**Files:**
- Create: `src/test/java/com/amazonaws/policyconverters/cedar/CedarToLFConverterCrossPolicyPropertyTest.java`

- [ ] **Step 1: Write the failing property test**

Create the file with the following content:

```java
package com.amazonaws.policyconverters.cedar;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.reporting.GapReporter;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property: if any forbid exists for (principal, action, resource) with no matching
 * deny-exception, the output must contain zero grants for that triple.
 */
class CedarToLFConverterCrossPolicyPropertyTest {

    // Fixed identifiers so we can reason about which triple is forbidden
    private static final String PRINCIPAL =
            "arn:aws:iam::123456789012:role/AnalystRole";
    private static final String TABLE_ARN =
            "arn:aws:glue:us-east-1:123456789012:table/testdb/orders";
    private static final String ACTION = "SELECT";

    private CedarToLFConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CedarToLFConverter(new CedarSchemaProvider(), new GapReporter(), null);
    }

    @Property(tries = 100)
    void forbidWithoutDenyExceptionAlwaysProducesZeroGrantsForThatTriple(
            @ForAll @IntRange(min = 0, max = 5) int extraPermits
    ) throws Exception {
        // Build a policy set with:
        //   - 1 forbid for (PRINCIPAL, ACTION, TABLE_ARN)
        //   - N extra permits for the same triple (from different "policies")
        //   - No deny-exception
        StringBuilder cedar = new StringBuilder();
        // The forbid
        cedar.append(String.format("""
                @source("forbid-policy")
                forbid(
                    principal == DataCatalog::Principal::"%s",
                    action == DataCatalog::Action::"%s",
                    resource == DataCatalog::Table::"%s"
                );
                """, PRINCIPAL, ACTION, TABLE_ARN));
        // Extra permits — all should be suppressed
        for (int i = 0; i < extraPermits; i++) {
            cedar.append(String.format("""
                    @source("permit-policy-%d")
                    permit(
                        principal == DataCatalog::Principal::"%s",
                        action == DataCatalog::Action::"%s",
                        resource == DataCatalog::Table::"%s"
                    );
                    """, i, PRINCIPAL, ACTION, TABLE_ARN));
        }

        CedarPolicySet policySet = CedarPolicySet.fromCedarString(cedar.toString());
        List<LFPermissionOperation> ops = converter.convert(policySet);

        long grantsForForbiddenTriple = ops.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn().equals(PRINCIPAL))
                .filter(op -> op.getPermissions().contains(LFPermission.SELECT))
                .filter(op -> "testdb".equals(op.getResource().getDatabaseName()))
                .filter(op -> "orders".equals(op.getResource().getTableName()))
                .count();

        assertEquals(0, grantsForForbiddenTriple,
                "A forbid with no deny-exception must suppress all permits for the same triple, " +
                "regardless of how many permit statements exist or which policies they come from. " +
                "extraPermits=" + extraPermits);
    }
}
```

- [ ] **Step 2: Run the property test**

```bash
mvn test -pl . -Dtest=CedarToLFConverterCrossPolicyPropertyTest -q 2>&1 | tail -20
```

Expected: PASS with `tries = 100`. If it fails, a real security regression exists — stop and fix the converter.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/cedar/CedarToLFConverterCrossPolicyPropertyTest.java
git commit -m "test: add property test for cross-policy forbid suppression"
```

---

## Task 3: Deny-Only Policy Produces Zero Grants (Unit)

**Files:**
- Modify: `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java`

A Ranger deny-only policy (no allow items, one or more deny items) must produce zero `LFPermissionOperation` entries. Currently `PolicyConverterTest` only asserts a gap record — this adds the explicit zero-grants assertion.

- [ ] **Step 1: Add the test**

Find the last `@Test` method in `PolicyConverterTest.java` and append this method before the closing `}` of the class:

```java
@Test
void denyOnlyPolicyProducesZeroGrantsAndRecordsGap() {
    // A Ranger policy with ONLY deny items must produce no LF grants.
    // Lake Formation has no deny model — deny items become gap records only.
    RangerPolicy policy = buildDenyOnlyPolicy(99L, "analytics", "events",
            Set.of("select"), "analyst");
    List<LFPermissionOperation> ops = converter.convert(
            policy, principalMapper, catalogResolver, gapReporter);

    assertEquals(0, ops.size(),
            "A deny-only Ranger policy must produce zero LFPermissionOperations. " +
            "Deny semantics are not enforceable via LF — they must be recorded as a gap only.");

    // Gap must be recorded so the operator knows deny policies are not being enforced
    assertFalse(gapReporter.getReport().getEntries().isEmpty(),
            "A deny-only policy must produce at least one gap record");
}
```

Also add this private helper method to the helpers section at the bottom of the class (after `buildPolicy`):

```java
private RangerPolicy buildDenyOnlyPolicy(long id, String db, String table,
                                          Set<String> accessTypes, String userName) {
    RangerPolicy policy = new RangerPolicy();
    policy.setId(id);
    policy.setName("deny-policy-" + id);
    policy.setService("lakeformation");
    policy.setIsEnabled(true);

    // Resource
    RangerPolicy.RangerPolicyResource dbResource = new RangerPolicy.RangerPolicyResource(db);
    RangerPolicy.RangerPolicyResource tableResource = new RangerPolicy.RangerPolicyResource(table);
    policy.setResources(Map.of("database", dbResource, "table", tableResource));

    // Deny item only — no allow items
    RangerPolicy.RangerPolicyItem denyItem = new RangerPolicy.RangerPolicyItem();
    denyItem.setUsers(List.of(userName));
    denyItem.setAccesses(accessTypes.stream()
            .map(a -> new RangerPolicy.RangerPolicyItemAccess(a, true))
            .collect(Collectors.toList()));
    policy.setDenyPolicyItems(List.of(denyItem));
    policy.setPolicyItems(List.of());  // no allow items

    return policy;
}
```

- [ ] **Step 2: Run the test**

```bash
mvn test -pl . -Dtest=PolicyConverterTest#denyOnlyPolicyProducesZeroGrantsAndRecordsGap -q 2>&1 | tail -20
```

Expected: PASS. If it fails with `ops.size() > 0`, that is a real security bug — the converter is turning deny items into grants. Do not skip.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java
git commit -m "test: assert deny-only policy produces zero grants (not just a gap record)"
```

---

## Task 4: All-Unmapped Principal Pipeline Test (Unit)

**Files:**
- Create: `src/test/java/com/amazonaws/policyconverters/PipelineUnmappedPrincipalTest.java`

Verifies the end-to-end security contract: if every user in a policy item is unmapped (not in the principal mapping config), no LF grant is produced, no exception is thrown, and a gap is recorded.

- [ ] **Step 1: Write the test**

```java
package com.amazonaws.policyconverters;

import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.ranger.*;
import com.amazonaws.policyconverters.ranger.service.HiveRangerService;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the security contract: every user in a policy item unmapped →
 * zero grants, no exception, gap recorded.
 */
class PipelineUnmappedPrincipalTest {

    private static final String CATALOG_ID = "123456789012";
    private PolicyConverter policyConverter;
    private StaticPrincipalMapper allUnmappedMapper;
    private CatalogResolver passthroughResolver;
    private GapReporter gapReporter;

    @BeforeEach
    void setUp() {
        policyConverter = new PolicyConverter(CATALOG_ID);
        // Empty mapping config — every user will be unmapped
        allUnmappedMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(Map.of(), Map.of(), Map.of()), null);
        passthroughResolver = Mockito.mock(CatalogResolver.class);
        Mockito.when(passthroughResolver.expandDatabases(Mockito.anyString()))
               .thenAnswer(inv -> List.of(inv.getArgument(0)));
        Mockito.when(passthroughResolver.expandTables(Mockito.anyString(), Mockito.anyString()))
               .thenAnswer(inv -> List.of(inv.getArgument(1)));
        gapReporter = new GapReporter();
    }

    @Test
    void allUnmappedUsersProduceZeroGrantsNoExceptionAndGapRecord() {
        RangerPolicy policy = buildPolicy(1L, "analytics", "events",
                Set.of("select"), List.of("unmapped_user_1", "unmapped_user_2"));

        List<LFPermissionOperation> ops = assertDoesNotThrow(
                () -> policyConverter.convert(policy, allUnmappedMapper, passthroughResolver, gapReporter),
                "Converting a policy with all-unmapped principals must never throw");

        assertEquals(0, ops.size(),
                "All principals unmapped → zero LFPermissionOperations. " +
                "An unmapped user must never receive any grant.");

        // The gap reporter must have been notified so operators can detect misconfiguration
        assertFalse(gapReporter.getReport().getEntries().isEmpty(),
                "Unmapped principals must produce at least one gap record for observability");
    }

    @Test
    void mixedMappedAndUnmappedProducesGrantOnlyForMappedUser() {
        // mapped_user has a mapping; unmapped_user does not
        StaticPrincipalMapper partialMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(
                        Map.of("mapped_user", "arn:aws:iam::123456789012:role/MappedRole"),
                        Map.of(), Map.of()),
                null);

        RangerPolicy policy = buildPolicy(2L, "analytics", "events",
                Set.of("select"), List.of("mapped_user", "unmapped_user"));
        List<LFPermissionOperation> ops = policyConverter.convert(
                policy, partialMapper, passthroughResolver, gapReporter);

        assertEquals(1, ops.size(),
                "Only the mapped user should produce a grant");
        assertEquals("arn:aws:iam::123456789012:role/MappedRole",
                ops.get(0).getPrincipalArn());
    }

    private RangerPolicy buildPolicy(long id, String db, String table,
                                      Set<String> accessTypes, List<String> users) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("test-policy-" + id);
        policy.setService("lakeformation");
        policy.setIsEnabled(true);
        policy.setResources(Map.of(
                "database", new RangerPolicy.RangerPolicyResource(db),
                "table",    new RangerPolicy.RangerPolicyResource(table)));
        RangerPolicy.RangerPolicyItem item = new RangerPolicy.RangerPolicyItem();
        item.setUsers(users);
        item.setAccesses(accessTypes.stream()
                .map(a -> new RangerPolicy.RangerPolicyItemAccess(a, true))
                .collect(java.util.stream.Collectors.toList()));
        policy.setPolicyItems(List.of(item));
        policy.setDenyPolicyItems(List.of());
        return policy;
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
mvn test -pl . -Dtest=PipelineUnmappedPrincipalTest -q 2>&1 | tail -20
```

Expected: both tests PASS. If `allUnmappedUsersProduceZeroGrantsNoExceptionAndGapRecord` fails with `ops.size() > 0`, stop — that is a security bug.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/PipelineUnmappedPrincipalTest.java
git commit -m "test: verify all-unmapped principal policy produces zero grants end-to-end"
```

---

## Task 5: grantable=true and grantable=false Propagation Tests (Unit)

**Files:**
- Modify: `src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java` (if `grantable` is set in `PolicyConverter`; adjust based on Step 1 findings)

**Background:** First, look at `SyncService.executeSyncCycle()` and `onPoliciesUpdated()` to find where `LFPermissionOperation` objects are constructed and how the `grantable` field is set from `RangerPolicy`. The `grantable` field on `LFPermissionOperation` comes from `RangerPolicyItem.isDelegateAdmin()`. You'll need to locate the exact code path.

- [ ] **Step 1: Read the relevant SyncService code**

```bash
grep -n "delegateAdmin\|grantable\|isGrantable" \
  src/main/java/com/amazonaws/policyconverters/sync/SyncService.java \
  src/main/java/com/amazonaws/policyconverters/ranger/PolicyConverter.java \
  src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java 2>&1 | head -40
```

This tells you which class sets `grantable` on `LFPermissionOperation`. The grantable flag may be set in `PolicyConverter` (from `RangerPolicyItem.isDelegateAdmin()`) or in `RangerToCedarConverter`. Understanding this determines whether the test belongs in `SyncServiceTest` (mock-based) or `PolicyConverterTest` (unit).

- [ ] **Step 2: Add the two tests to the appropriate test class**

If `grantable` is set in `PolicyConverter`, add to `PolicyConverterTest.java`. If it originates in the Cedar layer, add to `CedarToLFConverterTest.java`. Based on the codebase exploration, add the following to the test class where `RangerPolicy.RangerPolicyItem.isDelegateAdmin()` is read:

```java
@Test
void delegateAdminFlagProducesGrantableOperation() {
    // Build policy where the policy item has delegateAdmin = true
    RangerPolicy policy = buildPolicy(10L, "analytics", "events",
            Set.of("select"), "analyst");
    policy.getPolicyItems().get(0).setDelegateAdmin(true);

    List<LFPermissionOperation> ops = converter.convert(
            policy, principalMapper, catalogResolver, gapReporter);

    assertEquals(1, ops.size());
    assertTrue(ops.get(0).isGrantable(),
            "A policy item with isDelegateAdmin=true must produce isGrantable=true. " +
            "Failing this means no principal ever gets GRANT OPTION, breaking delegated admin.");
}

@Test
void missingDelegateAdminFlagProducesNonGrantableOperation() {
    // Default policy: no delegateAdmin flag set — must produce grantable=false
    RangerPolicy policy = buildPolicy(11L, "analytics", "events",
            Set.of("select"), "analyst");
    // Do NOT set delegateAdmin — default should be false

    List<LFPermissionOperation> ops = converter.convert(
            policy, principalMapper, catalogResolver, gapReporter);

    assertEquals(1, ops.size());
    assertFalse(ops.get(0).isGrantable(),
            "A policy item without isDelegateAdmin must produce isGrantable=false. " +
            "Failing this means every principal silently gets GRANT OPTION, " +
            "a privilege escalation vulnerability.");
}
```

- [ ] **Step 3: Run the tests**

```bash
mvn test -pl . -Dtest=PolicyConverterTest#delegateAdminFlagProducesGrantableOperation+missingDelegateAdminFlagProducesNonGrantableOperation -q 2>&1 | tail -20
```

(Adjust the test class name if you placed them elsewhere based on step 1 findings.)

Expected: both PASS. If `missingDelegateAdminFlagProducesNonGrantableOperation` fails (`isGrantable` defaults to `true`), that is a privilege escalation bug — stop and fix.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/ranger/PolicyConverterTest.java  # or wherever added
git commit -m "test: assert grantable=true and grantable=false default propagation"
```

---

## Task 6: Row Filter Pass-Through Safety Tests (Unit)

**Files:**
- Create: `src/test/java/com/amazonaws/policyconverters/RowFilterPassThroughTest.java`

**Background:** Row filters are parsed from Cedar text by `ROW_FILTER_PATTERN` in `CedarToLFConverter` and stored as `LFResource.rowFilterExpression`. This test verifies that SQL metacharacters are preserved exactly (byte-equality) and defines the behavior for overlong strings.

**Design decision for overlong strings:** Check whether LF's API has a documented limit. The `BatchGrantPermissions` API does not document a `rowFilterExpression` length limit in the AWS SDK, so the safe default is: **pass through verbatim** (let the LF API reject it if needed, rather than silently dropping the filter, which would produce a more permissive policy).

- [ ] **Step 1: Write the test**

```java
package com.amazonaws.policyconverters;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies row filter expressions are preserved with byte-equality through the
 * Cedar→LF conversion pipeline. A silently dropped or truncated row filter
 * produces a more permissive LF policy than intended — a security miss.
 */
class RowFilterPassThroughTest {

    private CedarToLFConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CedarToLFConverter(new CedarSchemaProvider(), new GapReporter(), null);
    }

    @Test
    void sqlMetacharactersPreservedWithByteEquality() throws Exception {
        // These characters appear in real SQL row filter expressions.
        // The converter must not escape, strip, or reject them.
        String filterExpression = "region = 'us-east-1' AND dept = \"sales\"; -- comment\nnot evil";

        String cedarText = buildCedarWithRowFilter(filterExpression);
        List<LFPermissionOperation> ops = converter.convert(CedarPolicySet.fromCedarString(cedarText));

        assertEquals(1, ops.size());
        assertEquals(OperationType.GRANT, ops.get(0).getOperationType());
        assertEquals(filterExpression,
                ops.get(0).getResource().getRowFilterExpression(),
                "Row filter expression must be preserved byte-for-byte. " +
                "Any mutation would silently change data access semantics.");
    }

    @Test
    void nullByteInRowFilterPreservedOrGapRecorded() throws Exception {
        // Null bytes in row filters are pathological but must not crash the service.
        // Acceptable outcomes: pass through OR record a gap (but NOT silently drop the filter
        // and produce an unfiltered grant).
        String filterWithNull = "col = 'value end'";
        String cedarText = buildCedarWithRowFilter(filterWithNull);

        // Must not throw
        List<LFPermissionOperation> ops = assertDoesNotThrow(
                () -> converter.convert(CedarPolicySet.fromCedarString(cedarText)));

        // If a grant was produced, the filter must be preserved exactly
        ops.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .forEach(op -> assertEquals(filterWithNull,
                        op.getResource().getRowFilterExpression(),
                        "If a grant is produced, the row filter must be preserved exactly. " +
                        "A null byte must not silently drop the filter expression."));
    }

    @Test
    void overlongRowFilterPassedThroughVerbatim() throws Exception {
        // Decision: pass through verbatim (let LF API enforce any length limits).
        // Silently truncating would produce a MORE permissive filter — a security miss.
        String longFilter = "col = '" + "x".repeat(5000) + "'";
        String cedarText = buildCedarWithRowFilter(longFilter);

        List<LFPermissionOperation> ops = assertDoesNotThrow(
                () -> converter.convert(CedarPolicySet.fromCedarString(cedarText)));

        // Must produce a grant with the full expression — no truncation
        assertEquals(1, ops.size(),
                "Overlong row filter must still produce a grant operation (pass through to LF API)");
        assertEquals(longFilter,
                ops.get(0).getResource().getRowFilterExpression(),
                "Overlong row filter must be passed through verbatim — truncation would " +
                "silently make the policy more permissive than intended.");
    }

    private String buildCedarWithRowFilter(String filterExpression) {
        // Escape for embedding in a Cedar string literal. Spaces are valid and must NOT be escaped.
        String escaped = filterExpression
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return String.format("""
                @source("policy-1")
                permit(
                    principal == DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole",
                    action == DataCatalog::Action::"SELECT",
                    resource == DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/testdb/orders"
                ) when {
                    resource.rowFilter == "%s"
                };
                """, escaped);
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
mvn test -pl . -Dtest=RowFilterPassThroughTest -q 2>&1 | tail -20
```

Expected: all three PASS. If `overlongRowFilterPassedThroughVerbatim` fails because the converter silently truncates the expression, that is a security bug (more permissive policy than intended).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/RowFilterPassThroughTest.java
git commit -m "test: verify row filter expressions pass through with byte-equality, including edge cases"
```

---

## Task 7: Cross-Policy Deny Interaction Integration Test

**Files:**
- Create: `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/CrossPolicyDenyInteractionIT.java`

**Background:** This test runs against a live Dockerized Ranger Admin (`http://localhost:6080`). It follows the exact same pattern as `TableGrantPolicyIT`. Two real Ranger policies are created — one allow, one deny — for the same user+resource. The dry-run output must contain zero grants for the denied user.

**Prerequisite:** Ranger Admin must be running. Run with `mvn verify -pl . -Dit.test=CrossPolicyDenyInteractionIT`.

- [ ] **Step 1: Write the test**

Look at the exact Ranger policy JSON format used in `TableGrantPolicyIT.java` (or `DatabaseGrantPolicyIT.java`) and model the JSON after it. The `createAndTrackPolicy` helper expects a JSON string matching the Ranger Admin REST API v2 policy format.

```java
package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.model.DryRunOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a Ranger deny policy suppresses a Ranger allow policy for the
 * same (user, resource) pair — the user must receive ZERO LF grants.
 *
 * Lake Formation has no native deny model. The denial must be implemented by
 * suppressing the Cedar permit during conversion, not by issuing an LF deny.
 */
public class CrossPolicyDenyInteractionIT extends DryRunPipelineIT {

    @Test
    void denyPolicySuppressesAllowPolicyForSameUserAndResource() throws Exception {
        // Policy A: allow analyst to SELECT on deny_test_db.deny_test_table
        String allowPolicy = """
                {
                  "service": "lakeformation",
                  "name": "cross-policy-allow",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["deny_test_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["deny_test_table"], "isExcludes": false, "isRecursive": false }
                  },
                  "policyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["analyst"],
                      "groups": [], "roles": [], "conditions": [], "delegateAdmin": false
                    }
                  ]
                }
                """;
        // Policy B: deny analyst SELECT on the same resource
        String denyPolicy = """
                {
                  "service": "lakeformation",
                  "name": "cross-policy-deny",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["deny_test_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["deny_test_table"], "isExcludes": false, "isRecursive": false }
                  },
                  "denyPolicyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["analyst"],
                      "groups": [], "roles": [], "conditions": [], "delegateAdmin": false
                    }
                  ]
                }
                """;

        createAndTrackPolicy(allowPolicy);
        createAndTrackPolicy(denyPolicy);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        List<LFPermissionOperation> grantsForAnalyst = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn().contains("analyst"))
                .filter(op -> "deny_test_db".equals(op.getResource().getDatabaseName()))
                .collect(Collectors.toList());

        assertEquals(0, grantsForAnalyst.size(),
                "A Ranger deny policy must suppress the allow policy for the same user and resource. " +
                "analyst must receive ZERO LF grants on deny_test_db.deny_test_table. " +
                "Actual grants: " + grantsForAnalyst);
    }
}
```

- [ ] **Step 2: Run the integration test (requires Ranger Admin on port 6080)**

```bash
mvn verify -pl . -Dit.test=CrossPolicyDenyInteractionIT -q 2>&1 | tail -30
```

Expected: PASS. If it fails with `grantsForAnalyst.size() > 0`, the deny→Cedar→LF pipeline is broken — the deny item is not being converted to a Cedar `forbid` statement. Investigate `RangerToCedarConverter` and `PolicyConverter`.

- [ ] **Step 3: Commit**

```bash
git add src/integration-test/java/com/amazonaws/policyconverters/ranger/it/CrossPolicyDenyInteractionIT.java
git commit -m "test(it): add cross-policy deny interaction integration test"
```

---

## Task 8: Wildcard Revocation on Table Removal Integration Test

**Files:**
- Create: `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/WildcardRevocationOnTableRemovalIT.java`

**Background:** This test overrides the `CatalogResolver` to simulate a table disappearing from Glue. The `DryRunPipelineIT` base class uses a passthrough resolver (returns the pattern as-is). This test replaces it with a mutable resolver that starts with 3 tables and removes 1 between cycles.

**Important:** The base class wires the `CatalogResolver` in `setUp()`. You need to override the setup to inject a custom resolver. Look at how `DryRunPipelineIT.setUp()` constructs `syncService` — you'll call `super.setUp()` and then rebuild `syncService` with the custom resolver, OR override a hook method if one exists.

- [ ] **Step 1: Read DryRunPipelineIT more carefully for override hooks**

```bash
grep -n "protected\|override\|catalogResolver" \
  src/integration-test/java/com/amazonaws/policyconverters/ranger/it/DryRunPipelineIT.java
```

If `catalogResolver` is a `protected` field, the subclass can reassign it in `@BeforeEach` after calling `super.setUp()`. If not, re-wire `syncService` using the same wiring code from `setUp()` but with the custom resolver.

- [ ] **Step 2: Write the test**

```java
package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that when a table is removed from the Glue catalog (simulated by
 * shrinking CatalogResolver output), the wildcard policy's grant for that table
 * is REVOKED on the next sync cycle.
 *
 * This is a security-critical path: a table that no longer exists should have its
 * permissions revoked, not left indefinitely in Lake Formation.
 */
public class WildcardRevocationOnTableRemovalIT extends DryRunPipelineIT {

    // Mutable list — we'll remove table3 between cycles to simulate Glue table removal
    private final List<String> availableTables = new ArrayList<>(List.of("wc_table1", "wc_table2", "wc_table3"));

    @BeforeEach
    @Override
    void setUp() throws Exception {
        super.setUp();
        // Re-wire syncService with a CatalogResolver that uses our mutable list
        // See DryRunPipelineIT for the exact wiring — replicate it here with the custom resolver
        // (Adjust based on what fields are accessible after reading the base class)
        rewireWithCustomCatalogResolver(new CatalogResolver() {
            @Override
            public List<String> expandDatabases(String pattern) {
                return List.of(pattern);
            }
            @Override
            public List<String> expandTables(String database, String tablePattern) {
                if (tablePattern.contains("*") || tablePattern.contains("?")) {
                    return new ArrayList<>(availableTables);  // copy — stable snapshot per call
                }
                return List.of(tablePattern);
            }
            @Override
            public List<String> expandColumns(String database, String table, String colPattern) {
                return List.of(colPattern);
            }
        });
    }

    @Test
    void tableRemovedFromGlueCausesRevocationOnNextCycle() throws Exception {
        // Wildcard policy: etl_user SELECT on wildcard_db.*
        String wildcardPolicy = """
                {
                  "service": "lakeformation",
                  "name": "wildcard-revocation-test",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["wildcard_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["*"],            "isExcludes": false, "isRecursive": false }
                  },
                  "policyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["etl_user"],
                      "groups": [], "roles": [], "conditions": [], "delegateAdmin": false
                    }
                  ]
                }
                """;

        // Cycle 1: 3 tables available — grants for wc_table1, wc_table2, wc_table3
        createAndTrackPolicy(wildcardPolicy);
        triggerSync();

        List<String> cycle1Tables = getGrantedTablesForUser("wildcard_db", "etl_user");
        assertTrue(cycle1Tables.containsAll(List.of("wc_table1", "wc_table2", "wc_table3")),
                "Cycle 1: grants expected for all 3 tables. Found: " + cycle1Tables);

        // Simulate wc_table3 being dropped from Glue
        availableTables.remove("wc_table3");
        clearDryRunOutputs();

        // Cycle 2: wc_table3 gone — trigger must produce a REVOKE for wc_table3
        triggerSync();

        List<DryRunOutput> cycle2Outputs = readDryRunOutputs();

        List<LFPermissionOperation> revokesForTable3 = cycle2Outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> "wildcard_db".equals(op.getResource().getDatabaseName()))
                .filter(op -> "wc_table3".equals(op.getResource().getTableName()))
                .filter(op -> op.getPrincipalArn().contains("etl_user"))
                .collect(Collectors.toList());

        assertFalse(revokesForTable3.isEmpty(),
                "wc_table3 was removed from the catalog — a REVOKE must be produced for etl_user. " +
                "Failing this means a removed table's permissions are never cleaned up in LF.");

        // wc_table1 and wc_table2 must NOT be revoked
        List<LFPermissionOperation> wrongRevokes = cycle2Outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> "wildcard_db".equals(op.getResource().getDatabaseName()))
                .filter(op -> List.of("wc_table1", "wc_table2").contains(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertTrue(wrongRevokes.isEmpty(),
                "Tables still in the catalog must not be revoked. Wrong revokes: " + wrongRevokes);
    }

    private List<String> getGrantedTablesForUser(String database, String userHint) throws Exception {
        return readDryRunOutputs().stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> database.equals(op.getResource().getDatabaseName()))
                .filter(op -> op.getPrincipalArn().contains(userHint))
                .map(op -> op.getResource().getTableName())
                .collect(Collectors.toList());
    }

    /**
     * Re-wires syncService with a custom CatalogResolver.
     * DryRunPipelineIT exposes dryRunClient (protected) and syncService (protected).
     * All other wiring vars are local to setUp() and are rebuilt here.
     * We stop the service started by super.setUp(), rebuild with the custom resolver, and restart.
     */
    protected void rewireWithCustomCatalogResolver(CatalogResolver resolver) throws Exception {
        if (syncService != null && syncService.isRunning()) {
            syncService.stop();
        }

        AwsContext awsContext = new AwsContext(TEST_REGION, TEST_ACCOUNT_ID, TEST_ACCOUNT_ID);
        RangerServiceAdapter lfAdapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        adapterRegistry.put("lakeformation", lfAdapter);

        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("analyst",    "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst");
        userMappings.put("etl_user",   "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user");
        userMappings.put("data_admin", "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin");
        userMappings.put("viewer",     "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/viewer");
        PrincipalMapper principalMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMappings, Collections.emptyMap(), Collections.emptyMap()), null);

        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider cedarSchemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, resolver, gapReporter, cedarSchemaProvider);
        CedarToLFConverter cedarToLFConverter = new CedarToLFConverter(
                cedarSchemaProvider, gapReporter, null);

        RangerPlugin plugin = new RangerPlugin();
        syncService = new SyncService(
                plugin, rangerToCedarConverter, cedarToLFConverter,
                dryRunClient, gapReporter, null);

        syncService.start(new SyncConfig(null, null, null, null, null, null, null));
    }
}
```

- [ ] **Step 3: Run the integration test**

```bash
mvn verify -pl . -Dit.test=WildcardRevocationOnTableRemovalIT -q 2>&1 | tail -30
```

Expected: PASS. If `revokesForTable3` is empty, the wildcard refresh path is not producing revokes on table removal — a real security gap.

- [ ] **Step 4: Commit**

```bash
git add src/integration-test/java/com/amazonaws/policyconverters/ranger/it/WildcardRevocationOnTableRemovalIT.java
git commit -m "test(it): add wildcard revocation on table removal integration test"
```

---

## Task 9: Grantable Permission Propagation Integration Test

**Files:**
- Create: `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/GrantablePermissionPropagationIT.java`

**Background:** Verifies that a Ranger policy with `delegateAdmin: true` produces a dry-run output entry with `permissionsWithGrantOption` populated (i.e., `isGrantable=true` on the `LFPermissionOperation`).

- [ ] **Step 1: Write the test**

```java
package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.model.DryRunOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a Ranger policy with delegateAdmin=true produces an LF permission
 * operation with isGrantable=true (permissionsWithGrantOption in the LF API call).
 */
public class GrantablePermissionPropagationIT extends DryRunPipelineIT {

    @Test
    void delegateAdminPolicyProducesGrantableOperation() throws Exception {
        String grantablePolicy = """
                {
                  "service": "lakeformation",
                  "name": "grantable-test-policy",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["grantable_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["grantable_table"], "isExcludes": false, "isRecursive": false }
                  },
                  "policyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["data_admin"],
                      "groups": [], "roles": [], "conditions": [],
                      "delegateAdmin": true
                    }
                  ]
                }
                """;

        createAndTrackPolicy(grantablePolicy);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        List<LFPermissionOperation> grantableOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn().contains("data_admin"))
                .filter(op -> "grantable_db".equals(op.getResource().getDatabaseName()))
                .filter(LFPermissionOperation::isGrantable)
                .collect(Collectors.toList());

        assertFalse(grantableOps.isEmpty(),
                "A policy with delegateAdmin=true must produce at least one operation with " +
                "isGrantable=true (permissionsWithGrantOption). " +
                "data_admin must be able to delegate permissions on grantable_db.grantable_table.");
    }

    @Test
    void nonDelegateAdminPolicyProducesNonGrantableOperation() throws Exception {
        String nonGrantablePolicy = """
                {
                  "service": "lakeformation",
                  "name": "non-grantable-test-policy",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["nongrantable_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["nongrantable_table"], "isExcludes": false, "isRecursive": false }
                  },
                  "policyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["analyst"],
                      "groups": [], "roles": [], "conditions": [],
                      "delegateAdmin": false
                    }
                  ]
                }
                """;

        createAndTrackPolicy(nonGrantablePolicy);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        List<LFPermissionOperation> wronglyGrantableOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn().contains("analyst"))
                .filter(op -> "nongrantable_db".equals(op.getResource().getDatabaseName()))
                .filter(LFPermissionOperation::isGrantable)
                .collect(Collectors.toList());

        assertTrue(wronglyGrantableOps.isEmpty(),
                "A policy without delegateAdmin must NOT produce any grantable operation. " +
                "analyst must not receive GRANT OPTION. Privilege escalation: " + wronglyGrantableOps);
    }
}
```

- [ ] **Step 2: Run the integration test**

```bash
mvn verify -pl . -Dit.test=GrantablePermissionPropagationIT -q 2>&1 | tail -30
```

Expected: both PASS.

- [ ] **Step 3: Commit**

```bash
git add src/integration-test/java/com/amazonaws/policyconverters/ranger/it/GrantablePermissionPropagationIT.java
git commit -m "test(it): add grantable permission propagation integration test"
```

---

## Task 10: Full Test Suite Run and Verification

- [ ] **Step 1: Run all unit tests**

```bash
mvn test -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, zero failures, zero errors.

- [ ] **Step 2: Run all integration tests (requires Ranger Admin)**

```bash
mvn verify -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`. All new IT classes pass.

- [ ] **Step 3: If any test fails, investigate before marking Track 1 complete**

A unit test failure here is a real security bug in the existing code — not a test problem. Do not skip or comment out failing assertions. Investigate, fix the production code, and re-run.

- [ ] **Step 4: Final commit if any cleanup needed**

```bash
git status  # verify clean
```
