# Known-Unmappable Action Gap Type Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the `UNSUPPORTED_ACTION` gap into two types so that Ranger access types we *deliberately* do not map (Hive `index`/`lock`, Presto/Trino `grant`/`revoke`) stop drowning the assessment report and stop demoting otherwise-clean policies to "partially convertible", while access types we have *never seen* stay loud.

**Architecture:** The adapters already know the difference — `HiveServiceAdapter` has `UNMAPPED_ACCESS_TYPES = Set.of("index", "lock")` and logs it at WARN, versus `LOG.error("Unknown Hive access type…")` for anything else. But `mapAccessTypeToCedarActions` returns `Collections.emptySet()` in both cases, so `RangerToCedarConverter` cannot tell them apart and emits an identical `UNSUPPORTED_ACTION` gap for both. This plan (1) exposes that existing distinction through a new `default` method on `SourcePolicyAdapter`, (2) adds a `KNOWN_UNMAPPABLE_ACTION` gap type the converter emits for the deliberate case, (3) marks it informational so it does not demote convertibility, and (4) aggregates it into one rollup per `(serviceType, accessType)` for all three assessment outputs.

**Tech Stack:** Java 17, Maven, JUnit 5, jqwik (property tests, min 100 iterations), Jackson.

---

## Scope and Decisions Already Made

These were settled with the user on 2026-09-11. Do not relitigate them.

| Decision | Choice |
|---|---|
| Suppress these gaps entirely (option 1)? | **No.** A policy whose only accesses are unmappable produces zero grants and must still state a reason. |
| Where does aggregation live? | **Assessment layer only.** `GapReporter` is shared with the live sync server and stays untouched this round. A follow-up is recorded in project memory (`future_ideas.md`). |
| Gaps file (`--output-gaps-path`) format | **Breaking change:** wrap the current bare JSON array in an object with `policies` + `knownUnmappableActions`. |
| Console format | Already approved by the user; reproduced in Task 7. |

**Explicitly out of scope:** the second `UNSUPPORTED_ACTION` emission site at `CedarToLFConverter.java:206`. That one fires when a *Cedar* action has no LF permission (e.g. `UPDATE`), which is a different concern from a Ranger access type with no Cedar mapping. Leave it emitting `UNSUPPORTED_ACTION`.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/main/java/com/amazonaws/policyconverters/model/GapEntry.java` | Shared gap value object | Add `KNOWN_UNMAPPABLE_ACTION` enum value; add nullable `serviceType`/`accessType` via an *additional* constructor |
| `src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java` | Adapter contract | Add `getKnownUnmappableAccessTypes()` + `isKnownUnmappableAccessType()` defaults |
| `src/main/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapter.java` | Hive mapping | Override the new default |
| `src/main/java/com/amazonaws/policyconverters/ranger/PrestoServiceAdapter.java` | Presto mapping | Override the new default |
| `src/main/java/com/amazonaws/policyconverters/ranger/TrinoServiceAdapter.java` | Trino mapping | Override the new default |
| `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java` | Ranger → Cedar | Branch the gap emission at `extractCedarActions` |
| `src/main/java/com/amazonaws/policyconverters/assessment/KnownUnmappableActionSummary.java` | Rollup row | **New** |
| `src/main/java/com/amazonaws/policyconverters/assessment/GapsFileOutput.java` | Gaps file envelope | **New** |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java` | Assessment result | Carry the rollup list |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java` | Orchestration + classification | Informational classification, NOT_CONVERTIBLE reason fallback, rollup build, collapsed gap report |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java` | All three outputs | Console section, gaps file envelope, explanation text |

### Why two new nullable fields on `GapEntry`

The rollup groups by `(serviceType, accessType)` and the console prints both. Deriving them by regex-parsing the `details` prose would be brittle, and `--output-gaps-path` exists precisely to be machine-readable. The addition is additive and Jackson-compatible (same pattern `AssessmentResult` used when `source`/`services` were added — see `docs/superpowers/specs/2026-06-09-assessment-file-mode-design.md`), and the existing 6-arg constructor is retained so **no existing call site changes**. Populate the fields only for the two action gap types; they stay `null` everywhere else and `@JsonInclude(NON_NULL)` keeps them out of unrelated JSON.

If the user vetoes touching the shared model, the fallback is to group by `details` string equality — the details text is fully determined by `(serviceType, accessType)` and contains no per-policy data, so it is an exact equivalent — but the console would then have to print prose instead of columns.

---

## Task 0: Stop duplicating action gaps per principal and per resource

**Prerequisite for correct counts in Task 5.** Found by the user running the tool on real policies: policy 1422 reported the identical `UNSUPPORTED_ACTION` message five times.

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java:106-111, 260-296, 316-321, 338, 379-380`
- Test: `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java`

### Why it duplicates

`extractCedarActions` records the gap, but is called at line 338 from inside
`for (principalArn) { for (resourceCombination) { … } }`, and `generateStatements` is itself invoked
up to four times per policy (allow items 260, deny items 269, allow-exceptions 278,
deny-exceptions 296). One entry is produced per **principal × resourceCombination × policyItem**,
when the finding is a property of `(policy, accessType)` only.

Deduping is information-preserving: the gap only fires when the *resource-level-agnostic* one-arg
overload `adapter.mapAccessTypeToCedarActions(accessType)` also returns empty (line 393-394), so it
cannot vary by resource level or principal. The sibling case — raw mapping non-empty but filtered at
this resource level — records no gap at all and must keep behaving that way.

- [ ] **Step 1: Write the failing test**

Append to `RangerToCedarConverterTest.java`. Use `hiveConverter` (see the note in Task 3).

```java
    @Test
    void unsupportedAccessTypeIsReportedOncePerPolicyNotPerPrincipal() {
        // Multiple principals on one policy: the access type is unmappable regardless of
        // principal, so the operator must see one finding, not one per principal.
        RangerPolicy policy = buildHiveTablePolicyWithPrincipals(
                List.of("alice", "bob", "carol"), List.of("select", "refresh"));

        hiveConverter.convert(List.of(policy));

        long refreshGaps = gapReporter.getReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION)
                .filter(g -> g.getDetails().contains("'refresh'"))
                .count();
        assertEquals(1, refreshGaps, "one entry per (policy, accessType)");
    }

    @Test
    void unsupportedAccessTypeIsReportedOncePerPolicyNotPerResource() {
        // Several tables in one policy — same reasoning.
        RangerPolicy policy = buildHiveMultiTablePolicy(
                List.of("orders", "events", "users"), List.of("refresh"));

        hiveConverter.convert(List.of(policy));

        assertEquals(1, gapReporter.getReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION)
                .count());
    }

    @Test
    void twoDistinctUnsupportedAccessTypesEachReportOnce() {
        RangerPolicy policy = buildHiveTablePolicyWithPrincipals(
                List.of("alice", "bob"), List.of("refresh", "repladmin"));

        hiveConverter.convert(List.of(policy));

        assertEquals(2, gapReporter.getReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION)
                .count());
    }

    @Test
    void separatePoliciesWithTheSameUnsupportedActionEachReportOnce() {
        // Dedup is per policy, not global — two policies must yield two findings.
        hiveConverter.convert(List.of(
                buildHiveTablePolicyWithPrincipals(1L, List.of("alice"), List.of("refresh")),
                buildHiveTablePolicyWithPrincipals(2L, List.of("alice"), List.of("refresh"))));

        assertEquals(2, gapReporter.getReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION)
                .count());
    }

    @Test
    void repeatedConvertCallsReportTheGapEachTime() {
        // Critical lifecycle guard: the sync server holds one long-lived converter and calls
        // convert() every cycle. Dedup state must NOT persist across calls, or the gap report
        // silently empties out after the first cycle.
        RangerPolicy policy = buildHiveTablePolicyWithPrincipals(List.of("alice"), List.of("refresh"));

        hiveConverter.convert(List.of(policy));
        hiveConverter.convert(List.of(policy));

        assertEquals(2, gapReporter.getReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION)
                .count(), "each convert() call reports afresh");
    }
```

> Write the `buildHive…` helpers on top of the existing policy-building code in the file. They must
> set a service name resolving to the `"hive"` registry key. Add an id-taking overload for the
> two-policy test.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mvn -q test -Dtest=RangerToCedarConverterTest
```

Expected: the first three FAIL with counts of 3, 3, and 4 respectively (the fan-out). The last two
should already pass — they are regression guards, and `repeatedConvertCallsReportTheGapEachTime` is
the one that will catch a wrong fix.

- [ ] **Step 3: Thread a per-policy dedup set through the call chain**

Scope the set to **one policy**, which is exactly the deduplication boundary the tests specify, and
which sidesteps the lifecycle hazard entirely — there is no state living longer than one
`convertSinglePolicy` call, so nothing can leak across policies or across `convert()` invocations.

Do **not** use an instance field. The sync server reuses one `RangerToCedarConverter` for the life of
the process and calls `convert()` every cycle; a field would suppress every gap after the first
policy of the first cycle.

In `convertSinglePolicy`, after `policyId` is assigned (line 111), declare:

```java
        // One entry per (accessType) for this policy: the gap emission in extractCedarActions
        // sits inside per-principal and per-resource loops, but the finding does not vary by
        // either. Scoped to this policy so nothing persists across policies or convert() calls.
        Set<String> reportedUnmappedAccessTypes = new HashSet<>();
```

Add `java.util.HashSet` to the imports if absent.

Pass it as a trailing parameter to all four `generateStatements(…)` call sites (lines 260, 269, 278,
296), add it to the `generateStatements` signature, and forward it to `extractCedarActions` at
line 338. Update the `generateStatements` Javadoc `@param` list.

- [ ] **Step 4: Guard the emission**

In `extractCedarActions`, add the parameter and wrap **only** the gap-recording block — not the
`actions.addAll(mapped)` path, and not the resource-level-filtered branch:

```java
                    if (rawMapped.isEmpty()) {
                        // Normalize the key so 'REFRESH' and 'refresh' in different policy items
                        // of the same policy collapse to one finding.
                        if (reportedUnmappedAccessTypes.add(accessType.trim().toLowerCase())) {
                            gapReporter.recordGap(new GapEntry(
                                    policyId, null, GapType.UNSUPPORTED_ACTION,
                                    null,
                                    "Access type '" + accessType + "' has no Cedar mapping for service '"
                                            + adapter.getServiceType() + "' and will be skipped.",
                                    "Review the Ranger policy and remove or replace the unsupported "
                                            + "access type."));
                        }
                    }
```

Task 3 later splits the body of this `if` into two branches. Keep the `reportedUnmappedAccessTypes`
guard wrapping **both** branches so the new `KNOWN_UNMAPPABLE_ACTION` inherits the fix — otherwise
Task 5's `occurrenceCount` counts principal×resource fan-out instead of policies, and the console
line overstates the impact.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -q test -Dtest='RangerToCedarConverterTest+RangerToCedarConverterPropertyTest'
```

Expected: PASS, all five new tests green.

- [ ] **Step 6: Check the effect on real data**

Existing gap counts recorded in project memory for the 133K-policy dataset list
`UNSUPPORTED_ACTION: 169`. That figure was inflated by this fan-out and should drop. Confirm the
direction of the change is a decrease and that no gap type other than `UNSUPPORTED_ACTION` moves.

- [ ] **Step 7: Run the full suite**

```bash
mvn -q test 2>&1 | tail -30
```

Any test asserting an exact `UNSUPPORTED_ACTION` count on a multi-principal or multi-resource policy
will now see a lower number. That is the fix working — update the expected value; do not reintroduce
the duplication.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java \
        src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java
git commit -m "fix: report unsupported access type once per policy, not per principal and resource"
```

---

## Task 1: Declare the known-unmappable capability on the adapter contract

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapter.java:47-48`
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/PrestoServiceAdapter.java:52-53`
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/TrinoServiceAdapter.java:52-53`
- Test: `src/test/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapterTest.java` (create if absent), `PrestoServiceAdapterTest.java`, `TrinoServiceAdapterTest.java`

- [ ] **Step 1: Write the failing tests**

There is no `HiveServiceAdapterTest.java` today — create it. Append to the existing Presto/Trino test classes.

In `src/test/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapterTest.java`:

```java
package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveServiceAdapterTest {

    private final HiveServiceAdapter adapter =
            new HiveServiceAdapter(new AwsContext("us-east-1", "123456789012", "123456789012"));

    @Test
    void indexAndLockAreDeclaredKnownUnmappable() {
        assertTrue(adapter.isKnownUnmappableAccessType("index"));
        assertTrue(adapter.isKnownUnmappableAccessType("lock"));
    }

    @Test
    void knownUnmappableMatchIsCaseAndWhitespaceInsensitive() {
        // Ranger policy JSON is not normalized; the converter passes access.getType() raw.
        assertTrue(adapter.isKnownUnmappableAccessType("  INDEX  "));
    }

    @Test
    void mappableAccessTypeIsNotKnownUnmappable() {
        assertFalse(adapter.isKnownUnmappableAccessType("select"));
    }

    @Test
    void unrecognizedAccessTypeIsNotKnownUnmappable() {
        // This is the critical case: an access type we have never seen must NOT be
        // classified as known-unmappable, or we lose our adapter-coverage signal.
        assertFalse(adapter.isKnownUnmappableAccessType("repladmin"));
    }

    @Test
    void nullAccessTypeIsNotKnownUnmappable() {
        assertFalse(adapter.isKnownUnmappableAccessType(null));
    }
}
```

Append to `PrestoServiceAdapterTest.java` (and the identical pair to `TrinoServiceAdapterTest.java`, changing the class under test):

```java
    @Test
    void grantAndRevokeAreDeclaredKnownUnmappable() {
        assertTrue(adapter.isKnownUnmappableAccessType("grant"));
        assertTrue(adapter.isKnownUnmappableAccessType("revoke"));
        assertFalse(adapter.isKnownUnmappableAccessType("select"));
    }
```

> This reuses the `adapter` field both test classes already populate in `@BeforeEach` (`PrestoServiceAdapterTest.java:29-35`, same pattern in `TrinoServiceAdapterTest`). Do not declare a local `adapter` — it would shadow the field.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mvn -q test -Dtest='HiveServiceAdapterTest+PrestoServiceAdapterTest+TrinoServiceAdapterTest'
```

Expected: compilation failure — `cannot find symbol: method isKnownUnmappableAccessType(String)`.

- [ ] **Step 3: Add the defaults to `SourcePolicyAdapter`**

Insert after the existing `mapAccessTypeToCedarActions(String, String)` default (currently ends at line 37) and add `java.util.Collections` to the imports:

```java
    /**
     * Access types this adapter recognises but deliberately does not map, because the
     * capability has no Lake Formation equivalent — e.g. Hive {@code index}/{@code lock},
     * Presto/Trino {@code grant}/{@code revoke}.
     *
     * <p><strong>This is not the same as an access type the adapter has never heard of.</strong>
     * An unrecognised type means the adapter itself has a coverage gap (a new Ranger release,
     * a custom access type) and must stay loud as an actionable {@code UNSUPPORTED_ACTION}.
     * A type listed here means "we know exactly what this is and it can never map", and is
     * reported as an informational {@code KNOWN_UNMAPPABLE_ACTION} that does not affect a
     * policy's convertibility classification.
     *
     * <p>Values must be lower-case; matching is done against a normalized input.
     * Defaults to empty — adapters with no such types need not override.
     */
    default Set<String> getKnownUnmappableAccessTypes() {
        return Collections.emptySet();
    }

    /**
     * Whether {@code sourceAccessType} is one this adapter deliberately does not map.
     * Normalizes the input, because callers pass the raw value straight from the Ranger
     * policy JSON. Adapters override {@link #getKnownUnmappableAccessTypes()}, not this.
     */
    default boolean isKnownUnmappableAccessType(String sourceAccessType) {
        if (sourceAccessType == null) {
            return false;
        }
        return getKnownUnmappableAccessTypes()
                .contains(sourceAccessType.trim().toLowerCase());
    }
```

- [ ] **Step 4: Override in the three adapters that have such types**

The `UNMAPPED_ACCESS_TYPES` constants already exist and are already lower-case. Just expose them. Add to `HiveServiceAdapter` (near `getServiceType()`, around line 74):

```java
    @Override
    public Set<String> getKnownUnmappableAccessTypes() {
        return UNMAPPED_ACCESS_TYPES;
    }
```

Add the identical override to `PrestoServiceAdapter` and `TrinoServiceAdapter`. Do **not** add it to `RangerServiceAdapter`, `EmrSparkServiceAdapter`, or `EmrfsServiceAdapter` — they have no such set, and the default empty is correct for them.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -q test -Dtest='HiveServiceAdapterTest+PrestoServiceAdapterTest+TrinoServiceAdapterTest'
```

Expected: PASS, all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java \
        src/main/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapter.java \
        src/main/java/com/amazonaws/policyconverters/ranger/PrestoServiceAdapter.java \
        src/main/java/com/amazonaws/policyconverters/ranger/TrinoServiceAdapter.java \
        src/test/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapterTest.java \
        src/test/java/com/amazonaws/policyconverters/ranger/PrestoServiceAdapterTest.java \
        src/test/java/com/amazonaws/policyconverters/ranger/TrinoServiceAdapterTest.java
git commit -m "feat: let adapters declare deliberately-unmappable access types"
```

---

## Task 2: Add the `KNOWN_UNMAPPABLE_ACTION` gap type and its structured fields

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/model/GapEntry.java`
- Test: `src/test/java/com/amazonaws/policyconverters/model/GapEntryTest.java`

- [ ] **Step 1: Write the failing test**

Append to `GapEntryTest.java`:

```java
    @Test
    void knownUnmappableActionGapTypeRoundTripsThroughJson() throws Exception {
        GapEntry entry = new GapEntry(
                "42", "hive-analyst", GapEntry.GapType.KNOWN_UNMAPPABLE_ACTION,
                null, "details", "recommendation", "hive", "index");

        // `mapper` is the existing field at GapEntryTest.java:10 — do not redeclare it.
        String json = mapper.writeValueAsString(entry);
        GapEntry parsed = mapper.readValue(json, GapEntry.class);

        assertEquals(entry, parsed);
        assertEquals("hive", parsed.getServiceType());
        assertEquals("index", parsed.getAccessType());
    }

    @Test
    void legacySixArgConstructorLeavesActionFieldsNullAndOutOfJson() throws Exception {
        GapEntry entry = new GapEntry(
                "42", "mask-ssn", GapEntry.GapType.DATA_MASKING, "db/tbl", "d", "r");

        assertNull(entry.getServiceType());
        assertNull(entry.getAccessType());

        String json = mapper.writeValueAsString(entry);
        assertFalse(json.contains("serviceType"), "NON_NULL must keep the field out of unrelated gaps");
        assertFalse(json.contains("accessType"));
    }
```

Add whatever imports the file is missing (`ObjectMapper`, `assertNull`, `assertFalse`).

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -q test -Dtest=GapEntryTest
```

Expected: compilation failure — no such enum constant `KNOWN_UNMAPPABLE_ACTION`, no 8-arg constructor.

- [ ] **Step 3: Implement**

In the `GapType` enum, insert directly after `UNSUPPORTED_ACTION` (line 31) so console output — which iterates `GapType.values()` — keeps the two action types adjacent:

```java
        UNSUPPORTED_ACTION("UNSUPPORTED_ACTION"),
        KNOWN_UNMAPPABLE_ACTION("KNOWN_UNMAPPABLE_ACTION"),
```

Add the two fields after `recommendation` (line 65):

```java
    /** Ranger service type; populated only for action-related gap types, else null. */
    private final String serviceType;
    /** Ranger access type; populated only for action-related gap types, else null. */
    private final String accessType;
```

Move `@JsonCreator` onto a new 8-arg constructor and make the existing 6-arg one delegate, so every current call site keeps compiling untouched:

```java
    public GapEntry(
            String policyId,
            String policyName,
            GapType gapType,
            String resourcePath,
            String details,
            String recommendation) {
        this(policyId, policyName, gapType, resourcePath, details, recommendation, null, null);
    }

    @JsonCreator
    public GapEntry(
            @JsonProperty("policyId") String policyId,
            @JsonProperty("policyName") String policyName,
            @JsonProperty("gapType") GapType gapType,
            @JsonProperty("resourcePath") String resourcePath,
            @JsonProperty("details") String details,
            @JsonProperty("recommendation") String recommendation,
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("accessType") String accessType) {
        this.policyId = policyId;
        this.policyName = policyName;
        this.gapType = gapType;
        this.resourcePath = resourcePath;
        this.details = details;
        this.recommendation = recommendation;
        this.serviceType = serviceType;
        this.accessType = accessType;
    }
```

Add the getters, and extend `equals`, `hashCode`, and `toString` to include both new fields. **Do not forget `equals`/`hashCode`** — Task 5 relies on `GapEntry` equality being correct.

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -q test -Dtest='GapEntryTest+GapReportTest'
```

Expected: PASS. `GapReportTest` is included because `computeSummary` switches on `GapType` values.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/model/GapEntry.java \
        src/test/java/com/amazonaws/policyconverters/model/GapEntryTest.java
git commit -m "feat: add KNOWN_UNMAPPABLE_ACTION gap type with structured action fields"
```

---

## Task 3: Emit the new gap type from the converter

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java:389-404`
- Test: `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java`

- [ ] **Step 1: Write the failing test**

Append to `RangerToCedarConverterTest.java`. Follow the fixture style already in that file for building a `RangerPolicy` and a converter — do not invent a new harness.

```java
    @Test
    void hiveIndexAccessTypeProducesKnownUnmappableGapNotUnsupportedAction() {
        // A Hive policy granting select + index. select converts; index is a capability
        // Lake Formation simply does not have, and must not be reported as actionable.
        RangerPolicy policy = buildHiveTablePolicy("analyst", List.of("select", "index"));

        hiveConverter.convert(List.of(policy));

        List<GapEntry> gaps = gapReporter.getReport().getEntries();
        assertTrue(gaps.stream().anyMatch(g ->
                        g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION
                                && "index".equals(g.getAccessType())
                                && "hive".equals(g.getServiceType())),
                "index must be reported as KNOWN_UNMAPPABLE_ACTION with structured fields");
        assertFalse(gaps.stream().anyMatch(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION),
                "index must NOT produce an actionable UNSUPPORTED_ACTION gap");
    }

    @Test
    void unrecognizedHiveAccessTypeStillProducesUnsupportedActionGap() {
        // The signal we must not lose: an access type no adapter knows about.
        RangerPolicy policy = buildHiveTablePolicy("analyst", List.of("repladmin"));

        hiveConverter.convert(List.of(policy));

        List<GapEntry> gaps = gapReporter.getReport().getEntries();
        assertTrue(gaps.stream().anyMatch(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION),
                "an unrecognised access type must stay loud");
        assertFalse(gaps.stream().anyMatch(g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION));
    }

    @Test
    void resourceLevelFilteredAccessTypeStillProducesNoGap() {
        // Regression guard on pre-existing behaviour: "select" at database level is a known
        // type filtered by resource level, which is intentional and must stay gap-free.
        RangerPolicy policy = buildHiveDatabasePolicy("analyst", List.of("select"));

        hiveConverter.convert(List.of(policy));

        assertTrue(gapReporter.getReport().getEntries().stream().noneMatch(g ->
                        g.getGapType() == GapType.UNSUPPORTED_ACTION
                                || g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION),
                "resource-level filtering of a known type is intentional — no gap");
    }
```

> **Use `hiveConverter`, not `converter`.** `RangerToCedarConverterTest` already has both (`RangerToCedarConverterTest.java:47-48`, wired at lines 69 and 76-77). Only `hiveConverter`'s registry contains `"hive" → HiveServiceAdapter`. If you use `converter`, `RangerToCedarConverter.convertSinglePolicy` (`RangerToCedarConverter.java:116-120`) silently falls back to the `"lakeformation"` adapter, which has no known-unmappable set — the test would fail against a correct implementation, or pass while exercising the wrong code path. Both converters share the same `gapReporter` field (line 66), so gap assertions work unchanged.
>
> If `buildHiveTablePolicy` / `buildHiveDatabasePolicy` helpers do not exist in the test class, write them as small private helpers modelled on the existing policy-building code in that file. Their policies must set a service name that resolves to the `"hive"` registry key.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -q test -Dtest=RangerToCedarConverterTest
```

Expected: the first two tests FAIL — `index` currently yields `UNSUPPORTED_ACTION`.

- [ ] **Step 3: Implement the branch**

Replace the `if (rawMapped.isEmpty()) { … }` body inside `extractCedarActions` with the following.

**Keep Task 0's `reportedUnmappedAccessTypes` guard wrapping both branches.** One shared key per
access type is correct: a given access type takes exactly one of the two branches, so a single set
cannot cause a known-unmappable finding to mask an unsupported one.

```java
                    if (rawMapped.isEmpty()
                            && reportedUnmappedAccessTypes.add(accessType.trim().toLowerCase())) {
                        if (adapter.isKnownUnmappableAccessType(accessType)) {
                            // Recognised, but Lake Formation has no such capability at all.
                            // Informational: it never affects convertibility, and the assessment
                            // layer rolls these up per (serviceType, accessType) rather than
                            // reporting one entry per policy.
                            gapReporter.recordGap(new GapEntry(
                                    policyId, null, GapType.KNOWN_UNMAPPABLE_ACTION,
                                    null,
                                    "Access type '" + accessType + "' has no Lake Formation equivalent "
                                            + "for service type '" + adapter.getServiceType()
                                            + "' and was dropped.",
                                    "No action required unless this capability is needed — Lake Formation "
                                            + "does not model it. Remove the access type from the Ranger "
                                            + "policy to silence this.",
                                    adapter.getServiceType(),
                                    accessType));
                        } else {
                            // Genuinely unknown — this is a coverage gap in our adapter and must
                            // stay actionable. Do not fold this into the case above.
                            gapReporter.recordGap(new GapEntry(
                                    policyId, null, GapType.UNSUPPORTED_ACTION,
                                    null,
                                    "Access type '" + accessType + "' has no Cedar mapping for service '"
                                            + adapter.getServiceType() + "' and will be skipped.",
                                    "Review the Ranger policy and remove or replace the unsupported "
                                            + "access type.",
                                    adapter.getServiceType(),
                                    accessType));
                        }
                    }
```

Keep the existing comment about `rawMapped` being non-empty (resource-level filtering, no gap) intact below this block.

Also add to Task 0's test set, to prove the dedup carries over to the new type:

```java
    @Test
    void knownUnmappableActionIsAlsoReportedOncePerPolicy() {
        RangerPolicy policy = buildHiveTablePolicyWithPrincipals(
                List.of("alice", "bob", "carol"), List.of("select", "index"));

        hiveConverter.convert(List.of(policy));

        assertEquals(1, gapReporter.getReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION)
                .count(), "Task 5's occurrenceCount depends on this being per-policy");
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -q test -Dtest='RangerToCedarConverterTest+RangerToCedarConverterPropertyTest'
```

Expected: PASS.

- [ ] **Step 5: Run the full unit suite to find fallout**

```bash
mvn -q test 2>&1 | tail -40
```

Expected: green. Nothing should break, but verify rather than assume.

`PipelineGapAggregationPropertyTest:61-160` and `SyncServiceTest:323-376` do assert on `UNSUPPORTED_ACTION`, but both construct `GapEntry` directly to simulate the `CedarToLFConverter` emission site — the one explicitly out of scope — and neither involves Hive/Presto/Trino access types. They should be unaffected.

If anything *does* fail, read the failure before changing it: if a test used a Hive `index`-style access type as a stand-in for "unsupported", switch its fixture to a genuinely unrecognised type (e.g. `"totally_made_up"`) so the test keeps testing what it meant to test, rather than weakening the assertion.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java src/test
git commit -m "feat: emit KNOWN_UNMAPPABLE_ACTION for deliberately unmapped access types"
```

---

## Task 4: Stop the new gap type from demoting convertibility

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java:262-265, 292-350`
- Test: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java`

This is the task that fixes the number customers actually make decisions on.

- [ ] **Step 1: Write the failing tests**

Append to `AssessmentRunnerTest.java`, following the existing `PolicySource` stub pattern in that file:

```java
    @Test
    void policyWithGrantsAndOnlyKnownUnmappableActionsIsFullyConvertible() {
        // hive policy: select (converts) + index (known-unmappable).
        // Before this change it was reported PARTIALLY_CONVERTIBLE, which is the headline
        // number customers plan against.
        AssessmentResult result = runAssessment(
                hivePolicy(1L, "analyst-select", List.of("select", "index")));

        assertEquals(1, result.getFullyConvertible());
        assertEquals(0, result.getPartiallyConvertible());
        assertEquals(0, result.getNotConvertible());
    }

    @Test
    void policyWithOnlyKnownUnmappableActionsIsNotConvertibleAndStatesTheReason() {
        // Zero grants produced. It must stay NOT_CONVERTIBLE *and* the gaps file entry
        // must explain why, or the report says "not convertible" with no reason.
        AssessmentResult result = runAssessment(
                hivePolicy(2L, "lock-only", List.of("index", "lock")));

        assertEquals(0, result.getFullyConvertible());
        assertEquals(1, result.getNotConvertible());

        PolicyGapSummary summary = result.getPolicyGapSummaries().stream()
                .filter(s -> "2".equals(s.getPolicyId()))
                .findFirst().orElseThrow();
        assertEquals(PolicyGapSummary.Convertibility.NOT_CONVERTIBLE, summary.getConvertibility());
        assertFalse(summary.getGaps().isEmpty(),
                "a NOT_CONVERTIBLE policy must never be reported with an empty gap list");
        assertTrue(summary.getGaps().stream().anyMatch(
                g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION));
    }

    @Test
    void unrecognizedActionStillDemotesToPartiallyConvertible() {
        AssessmentResult result = runAssessment(
                hivePolicy(3L, "mixed", List.of("select", "totally_made_up")));

        assertEquals(0, result.getFullyConvertible());
        assertEquals(1, result.getPartiallyConvertible());
    }
```

> `runAssessment(...)` and `hivePolicy(...)` are helpers you write on top of whatever `PolicySource` stub the class already uses. Keep them private to the test class.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mvn -q test -Dtest=AssessmentRunnerTest
```

Expected: test 1 fails (`expected 1 fully convertible but was 0`); test 2 fails on the empty gap list.

- [ ] **Step 3: Add the type to the informational set**

At line 262, extend the set and update its comment, which is currently narrower than the truth (the set already drives convertibility, not just the output file):

```java
    // Gap types that are informational only: they never demote a policy's convertibility
    // classification and are excluded from the per-policy entries in the gaps output file.
    private static final Set<GapEntry.GapType> INFORMATIONAL_GAP_TYPES = new HashSet<>(Arrays.asList(
            GapEntry.GapType.CANNOT_VALIDATE_S3_LOCATION,
            GapEntry.GapType.WILDCARD_PATTERN,
            GapEntry.GapType.KNOWN_UNMAPPABLE_ACTION
    ));
```

- [ ] **Step 4: Keep a reason on NOT_CONVERTIBLE policies**

In `computeConvertibility`, build a second index alongside the existing one. Replace the loop at lines 292-300 with:

```java
        // Actionable gaps drive classification. Informational gaps are tracked separately so a
        // policy that converts to nothing *solely* because of them can still state a reason.
        Map<String, List<GapEntry>> actionableGapsByPolicy = new LinkedHashMap<>();
        Map<String, List<GapEntry>> informationalGapsByPolicy = new LinkedHashMap<>();
        for (GapEntry entry : gapReport.getEntries()) {
            if (entry.getPolicyId() == null) {
                continue;
            }
            Map<String, List<GapEntry>> target =
                    INFORMATIONAL_GAP_TYPES.contains(entry.getGapType())
                            ? informationalGapsByPolicy : actionableGapsByPolicy;
            target.computeIfAbsent(entry.getPolicyId(), k -> new ArrayList<>()).add(entry);
        }
```

Then in the per-policy loop, change only the NOT_CONVERTIBLE branch (lines 342-345):

```java
            } else {
                notConvertible++;
                convertibility = PolicyGapSummary.Convertibility.NOT_CONVERTIBLE;
                if (gaps.isEmpty() && id != null) {
                    // Nothing actionable, yet it produced no grants. Surface the informational
                    // gaps so the report never says "not convertible" without saying why.
                    gaps = informationalGapsByPolicy.getOrDefault(id, Collections.emptyList());
                }
            }
```

`gaps` is currently effectively final — change its declaration at line 330 from `List<GapEntry> gaps = …` to a mutable local (it already is a local; just ensure it is not declared `final`).

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -q test -Dtest=AssessmentRunnerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java
git commit -m "fix: known-unmappable actions no longer demote policy convertibility"
```

---

## Task 5: Aggregate into a rollup and collapse the assessment JSON

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/KnownUnmappableActionSummary.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java:182-197`
- Test: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java`

Ordering matters: `computeConvertibility` must run against the **raw** per-policy entries (Task 4 depends on `policyId`), and only afterwards is the report collapsed for serialization.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void knownUnmappableActionsAreRolledUpAndCollapsedInTheGapReport() {
        AssessmentResult result = runAssessment(
                hivePolicy(1L, "a", List.of("select", "index")),
                hivePolicy(2L, "b", List.of("select", "index")),
                hivePolicy(3L, "c", List.of("select", "lock")));

        // Rollup: one row per (serviceType, accessType), with occurrence counts.
        List<KnownUnmappableActionSummary> rollup = result.getKnownUnmappableActions();
        assertEquals(2, rollup.size());

        KnownUnmappableActionSummary index = rollup.stream()
                .filter(r -> "index".equals(r.getAccessType())).findFirst().orElseThrow();
        assertEquals("hive", index.getServiceType());
        assertEquals(2, index.getOccurrenceCount());
        assertEquals(List.of("1", "2"), index.getSamplePolicyIds());

        // Gap report: collapsed to one entry per (serviceType, accessType), not per policy.
        long entries = result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION)
                .count();
        assertEquals(2, entries);
        assertTrue(result.getGapReport().getEntries().stream()
                        .filter(g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION)
                        .allMatch(g -> g.getPolicyId() == null),
                "collapsed entries are not policy-scoped and must not claim a policyId");

        // And the summary count matches the collapsed entry count.
        assertEquals(Integer.valueOf(2),
                result.getGapReport().getSummary().get(GapType.KNOWN_UNMAPPABLE_ACTION));
    }

    @Test
    void samplePolicyIdsAreCappedButOccurrenceCountIsNot() {
        RangerPolicy[] policies = new RangerPolicy[25];
        for (int i = 0; i < 25; i++) {
            policies[i] = hivePolicy(i + 1L, "p" + i, List.of("select", "index"));
        }

        AssessmentResult result = runAssessment(policies);

        KnownUnmappableActionSummary index = result.getKnownUnmappableActions().get(0);
        assertEquals(25, index.getOccurrenceCount());
        assertEquals(10, index.getSamplePolicyIds().size(), "samples are capped at 10");
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -q test -Dtest=AssessmentRunnerTest
```

Expected: compilation failure — `KnownUnmappableActionSummary` does not exist.

- [ ] **Step 3: Create the rollup model**

`src/main/java/com/amazonaws/policyconverters/assessment/KnownUnmappableActionSummary.java`:

```java
package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * One row of the known-unmappable-action rollup: a Ranger access type that a service
 * adapter deliberately does not map because Lake Formation has no equivalent, together
 * with how many policies used it.
 *
 * <p>Aggregated per {@code (serviceType, accessType)} rather than per policy — a single
 * unmappable access type routinely appears in thousands of policies, and one entry each
 * would swamp every output the tool produces.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KnownUnmappableActionSummary {

    /** Upper bound on {@code samplePolicyIds}; the count is exact regardless. */
    public static final int SAMPLE_LIMIT = 10;

    private final String serviceType;
    private final String accessType;
    private final int occurrenceCount;
    private final List<String> samplePolicyIds;

    @JsonCreator
    public KnownUnmappableActionSummary(
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("accessType") String accessType,
            @JsonProperty("occurrenceCount") int occurrenceCount,
            @JsonProperty("samplePolicyIds") List<String> samplePolicyIds) {
        this.serviceType = serviceType;
        this.accessType = accessType;
        this.occurrenceCount = occurrenceCount;
        this.samplePolicyIds = samplePolicyIds != null
                ? Collections.unmodifiableList(samplePolicyIds) : Collections.emptyList();
    }

    public String getServiceType()          { return serviceType; }
    public String getAccessType()           { return accessType; }
    public int getOccurrenceCount()         { return occurrenceCount; }
    public List<String> getSamplePolicyIds() { return samplePolicyIds; }
}
```

- [ ] **Step 4: Add the aggregation to `AssessmentRunner`**

Add two private static helpers:

```java
    /**
     * Rolls KNOWN_UNMAPPABLE_ACTION entries up to one row per (serviceType, accessType),
     * ordered by descending occurrence count so the console leads with the worst offender.
     */
    private static List<KnownUnmappableActionSummary> buildKnownUnmappableRollup(GapReport report) {
        // LinkedHashMap keeps first-seen order stable before the final sort, which keeps
        // output deterministic for equal counts (important for the demo's expected output).
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, List<String>> samples = new LinkedHashMap<>();
        Map<String, String[]> keyParts = new LinkedHashMap<>();

        for (GapEntry entry : report.getEntries()) {
            if (entry.getGapType() != GapType.KNOWN_UNMAPPABLE_ACTION) {
                continue;
            }
            String key = entry.getServiceType() + " " + entry.getAccessType();
            keyParts.putIfAbsent(key, new String[]{entry.getServiceType(), entry.getAccessType()});
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            List<String> sample = samples.computeIfAbsent(key, k -> new ArrayList<>());
            if (entry.getPolicyId() != null
                    && sample.size() < KnownUnmappableActionSummary.SAMPLE_LIMIT) {
                sample.add(entry.getPolicyId());
            }
        }

        List<KnownUnmappableActionSummary> rollup = new ArrayList<>();
        for (Map.Entry<String, String[]> e : keyParts.entrySet()) {
            rollup.add(new KnownUnmappableActionSummary(
                    e.getValue()[0], e.getValue()[1],
                    counts.get(e.getKey())[0], samples.get(e.getKey())));
        }
        rollup.sort(Comparator.comparingInt(KnownUnmappableActionSummary::getOccurrenceCount)
                .reversed());
        return rollup;
    }

    /**
     * Returns a copy of {@code report} with KNOWN_UNMAPPABLE_ACTION entries replaced by one
     * collapsed entry per (serviceType, accessType). Prevents the assessment JSON from being
     * dominated by hundreds of thousands of identical objects on a large estate.
     */
    private static GapReport collapseKnownUnmappableEntries(
            GapReport report, List<KnownUnmappableActionSummary> rollup) {
        List<GapEntry> collapsed = new ArrayList<>();
        for (GapEntry entry : report.getEntries()) {
            if (entry.getGapType() != GapType.KNOWN_UNMAPPABLE_ACTION) {
                collapsed.add(entry);
            }
        }
        for (KnownUnmappableActionSummary row : rollup) {
            collapsed.add(new GapEntry(
                    null, null, GapType.KNOWN_UNMAPPABLE_ACTION, null,
                    "Access type '" + row.getAccessType() + "' has no Lake Formation equivalent "
                            + "for service type '" + row.getServiceType() + "' and was dropped in "
                            + row.getOccurrenceCount() + " policy item(s).",
                    "No action required unless this capability is needed — Lake Formation does not "
                            + "model it. Remove the access type from the Ranger policies to silence this.",
                    row.getServiceType(), row.getAccessType()));
        }
        return new GapReport(collapsed, GapReport.computeSummary(collapsed), report.getGeneratedAt());
    }
```

Add `java.util.Comparator` to the imports.

Now wire it in, replacing lines 182-197. Note the order — convertibility is computed on the **raw** report:

```java
        GapReport rawGapReport = gapReporter.getReport();

        // Classification runs on the raw, policy-scoped entries — Task 4's NOT_CONVERTIBLE
        // reason lookup needs per-policy granularity.
        ConvertibilityResult convertibility =
                computeConvertibility(allPolicies, ops, s3AgOps, rawGapReport);

        List<KnownUnmappableActionSummary> knownUnmappable =
                buildKnownUnmappableRollup(rawGapReport);
        GapReport gapReport = collapseKnownUnmappableEntries(rawGapReport, knownUnmappable);

        return new AssessmentResult(
                allPolicies.size(),
                convertibility.fullyConvertible,
                convertibility.partiallyConvertible,
                convertibility.notConvertible,
                ops.size(),
                gapReport,
                source.sourceLabel(),
                assessedServices,
                warnings,
                ops,
                convertibility.policyGapSummaries,
                knownUnmappable);
```

- [ ] **Step 5: Carry the rollup on `AssessmentResult`**

Add a `private final List<KnownUnmappableActionSummary> knownUnmappableActions;` field. Extend the long (non-`@JsonCreator`) constructor with a trailing parameter, defaulting to `Collections.emptyList()` when null. Have the `@JsonCreator` constructor pass `Collections.emptyList()`, matching how it already handles `projectedOperations`. Add the getter — this one **is** serialized (unlike `getProjectedOperations`), so annotate it:

```java
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<KnownUnmappableActionSummary> getKnownUnmappableActions() {
        return knownUnmappableActions;
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
mvn -q test -Dtest='AssessmentRunnerTest+AssessmentResultTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/ \
        src/test/java/com/amazonaws/policyconverters/assessment/
git commit -m "feat: roll up and collapse known-unmappable action gaps in assessment output"
```

---

## Task 6: Console output section

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java:34-70, 166-216`
- Test: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java`

This is the format the user already approved.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void printsKnownUnmappableActionRollupAsItsOwnSection() {
        AssessmentResult result = resultWithKnownUnmappable(List.of(
                new KnownUnmappableActionSummary("hive", "index", 1204, List.of("1", "2")),
                new KnownUnmappableActionSummary("hive", "lock", 1198, List.of("3")),
                new KnownUnmappableActionSummary("trino", "grant", 12, List.of("4"))));

        String output = capture(result);

        assertTrue(output.contains("Known-unmappable access types"));
        assertTrue(output.contains("hive: index"));
        assertTrue(output.contains("1204"));
        assertTrue(output.contains("trino: grant"));
    }

    @Test
    void knownUnmappableActionsAreNotListedInThePerPolicyBreakdown() {
        // The whole point: one policy per line for 1204 policies is what we are removing.
        AssessmentResult result = resultWithKnownUnmappable(List.of(
                new KnownUnmappableActionSummary("hive", "index", 1204, List.of("1"))));

        String output = capture(result);

        assertFalse(output.contains("Policies with gaps"),
                "no actionable gaps means no per-policy breakdown");
    }
```

Write `capture(...)` as a helper that reports into a `ByteArrayOutputStream`-backed `PrintStream` with `consoleOnly=true`, and `resultWithKnownUnmappable(...)` building an `AssessmentResult`. Reuse existing helpers in the file if present.

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -q test -Dtest=AssessmentReporterTest
```

Expected: FAIL — output does not contain the section header.

- [ ] **Step 3: Add the explanation text**

In the `GAP_EXPLANATIONS` static block, after the `UNSUPPORTED_ACTION` entry:

```java
        GAP_EXPLANATIONS.put(GapEntry.GapType.KNOWN_UNMAPPABLE_ACTION,
                "Access types with no Lake Formation equivalent by design; dropped, and not counted "
                        + "against convertibility. See the rollup below.");
```

- [ ] **Step 4: Print the section**

In `printConsoleReport`, insert after the gap-type summary loop and before the "Unmapped resource paths" block (i.e. after line 176):

```java
            List<KnownUnmappableActionSummary> knownUnmappable = result.getKnownUnmappableActions();
            if (!knownUnmappable.isEmpty()) {
                out.println();
                out.println("Known-unmappable access types (informational — no Lake Formation "
                        + "equivalent, no effect on convertibility):");
                for (KnownUnmappableActionSummary row : knownUnmappable) {
                    // Plain %7d, not %,7d — nothing else in this file's console output uses
                    // grouped thousands separators, and a locale-dependent separator would
                    // make output assertions locale-dependent too.
                    out.printf("  %-24s — %7d policy items%n",
                            row.getServiceType() + ": " + row.getAccessType(),
                            row.getOccurrenceCount());
                }
            }
```

Note this sits inside the existing `else` branch of `if (totalGaps == 0)`. Because the collapsed report still contributes to `totalGaps`, a run whose *only* gaps are known-unmappable will reach this branch — which is what we want. Verify that case in the test.

- [ ] **Step 5: Keep the collapsed entries out of the per-policy breakdown**

Without this the second test fails. The per-policy grouping loop (`AssessmentReporter.java:194-198`) buckets any entry with a null `policyId` under `"<unknown>"`, and Task 5's collapsed `KNOWN_UNMAPPABLE_ACTION` entries all have `policyId == null`. So they would print as a bogus `Policy: id=<unknown>` block *and* force the `"Policies with gaps (N):"` header to appear even when there is nothing actionable — exactly the noise this whole change removes.

Filter them out when building the map:

```java
            // Per-policy breakdown grouped by policy. Known-unmappable entries are excluded:
            // they are collapsed (policyId == null) and already shown in their own rollup
            // section above, so they must not create an "<unknown>" pseudo-policy here.
            Map<String, List<GapEntry>> byPolicy = new LinkedHashMap<>();
            for (GapEntry entry : result.getGapReport().getEntries()) {
                if (entry.getGapType() == GapEntry.GapType.KNOWN_UNMAPPABLE_ACTION) {
                    continue;
                }
                String key = entry.getPolicyId() != null ? entry.getPolicyId() : "<unknown>";
                byPolicy.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
```

Then guard the header and loop so an empty map prints nothing:

```java
            if (!byPolicy.isEmpty()) {
                out.println();
                out.printf("Policies with gaps (%d):%n", byPolicy.size());
                for (List<GapEntry> entries : byPolicy.values()) {
                    // … existing body unchanged …
                }
            }
```

- [ ] **Step 6: Run to verify it passes**

```bash
mvn -q test -Dtest=AssessmentReporterTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java
git commit -m "feat: add known-unmappable action rollup section to assessment console output"
```

---

## Task 7: Gaps file envelope (breaking format change)

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/GapsFileOutput.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java:220-225`
- Test: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void gapsFileIsAnObjectWithPoliciesAndKnownUnmappableRollup(@TempDir Path tmp)
            throws Exception {
        Path gapsPath = tmp.resolve("gaps.json");
        AssessmentResult result = /* … one PARTIALLY_CONVERTIBLE policy + one rollup row … */;

        new AssessmentReporter().report(result, configWithGapsPath(gapsPath),
                new PrintStream(new ByteArrayOutputStream()));

        JsonNode root = new ObjectMapper().readTree(gapsPath.toFile());
        assertTrue(root.isObject(), "gaps file is now an object, not a bare array");
        assertTrue(root.get("policies").isArray());
        assertEquals(1, root.get("policies").size());
        assertEquals("index", root.get("knownUnmappableActions").get(0).get("accessType").asText());
        assertEquals(1204,
                root.get("knownUnmappableActions").get(0).get("occurrenceCount").asInt());
    }

    @Test
    void gapsFileOmitsRollupKeyWhenThereAreNoKnownUnmappableActions(@TempDir Path tmp)
            throws Exception {
        Path gapsPath = tmp.resolve("gaps.json");

        new AssessmentReporter().report(resultWithNoKnownUnmappable(),
                configWithGapsPath(gapsPath), new PrintStream(new ByteArrayOutputStream()));

        JsonNode root = new ObjectMapper().readTree(gapsPath.toFile());
        assertFalse(root.has("knownUnmappableActions"));
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -q test -Dtest=AssessmentReporterTest
```

Expected: FAIL — the written JSON is still an array.

- [ ] **Step 3: Create the envelope**

`src/main/java/com/amazonaws/policyconverters/assessment/GapsFileOutput.java`:

```java
package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Envelope for the {@code --output-gaps-path} file.
 *
 * <p>Replaces the bare {@code PolicyGapSummary[]} array this file used to be, which had
 * nowhere to put the known-unmappable-action rollup. This is a breaking change for anything
 * parsing the old array — see the README migration note.
 */
public class GapsFileOutput {

    private final List<PolicyGapSummary> policies;
    private final List<KnownUnmappableActionSummary> knownUnmappableActions;

    @JsonCreator
    public GapsFileOutput(
            @JsonProperty("policies") List<PolicyGapSummary> policies,
            @JsonProperty("knownUnmappableActions")
                    List<KnownUnmappableActionSummary> knownUnmappableActions) {
        this.policies = policies != null
                ? Collections.unmodifiableList(policies) : Collections.emptyList();
        this.knownUnmappableActions = knownUnmappableActions != null
                ? Collections.unmodifiableList(knownUnmappableActions) : Collections.emptyList();
    }

    public List<PolicyGapSummary> getPolicies() { return policies; }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<KnownUnmappableActionSummary> getKnownUnmappableActions() {
        return knownUnmappableActions;
    }
}
```

- [ ] **Step 4: Write the envelope instead of the array**

Replace the body of `writeGapsFile`:

```java
    private void writeGapsFile(AssessmentResult result, Path outputPath) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        objectMapper.writeValue(outputPath.toFile(), new GapsFileOutput(
                result.getPolicyGapSummaries(), result.getKnownUnmappableActions()));
        LOG.info("Gap report written to {}", outputPath.toAbsolutePath());
    }
```

- [ ] **Step 5: Run to verify it passes**

```bash
mvn -q test -Dtest=AssessmentReporterTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/GapsFileOutput.java \
        src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java
git commit -m "feat!: wrap gaps output file in an object with known-unmappable rollup"
```

---

## Task 8: Verify end-to-end against the demo, then document

**Files:**
- Modify: `README.md`
- Modify: `docs/design/05-ranger-services.md:87, 123`
- Modify: `docs/design/09-models-and-reporting.md:19-21`
- Modify: `demo/README.md`

- [ ] **Step 1: Run the full suite**

```bash
mvn -q test 2>&1 | tail -30
```

Expected: all green. Fix anything outstanding before documenting.

- [ ] **Step 2: Run the demo end-to-end and capture real output**

```bash
mvn clean package -DskipTests
mkdir -p demo/out
java -jar target/assessment-jar-with-dependencies.jar \
  file demo/Demo_Ranger_Policies.json \
  --config demo/demo-config.yaml \
  --output-lf-policies-path demo/out/lf_operations.json \
  --output-gaps-path demo/out/gaps.json \
  --console-only
```

The demo export contains Hive and Trino policies, so it should exercise the new path. Inspect the result:

```bash
python3 -m json.tool demo/out/gaps.json | head -40
```

Confirm: `policies` array present, `knownUnmappableActions` rollup present, convertibility counts shifted toward "fully convertible" versus the numbers currently pinned in `demo/README.md`.

> If the demo export happens to contain no `index`/`lock`/`grant`/`revoke` access types, the new section will be absent. In that case add a couple of such policies to `demo/generate_demo_policies.py` (edit the generator, **not** the JSON — the demo README says so explicitly), regenerate, and re-run. A demo that does not exercise this path is not worth keeping.

- [ ] **Step 3: Update `demo/README.md`**

Replace the "Expected output" block with the real captured console output, including the new section.

- [ ] **Step 4: Update the design docs**

`docs/design/05-ranger-services.md` — two corrections:
- Line 87 (Hive): note that `index`/`lock` are declared via `getKnownUnmappableAccessTypes()` and surface as `KNOWN_UNMAPPABLE_ACTION`.
- Line 123 (Presto): the current text says `grant`, `revoke` are "silently skipped". That was never true — they produced a gap. Correct it to say they are declared known-unmappable and reported as `KNOWN_UNMAPPABLE_ACTION`. Apply the same to the Trino section, which defers to Presto.

`docs/design/09-models-and-reporting.md` — update the `GapType` enum list (line 19 says "16 values"; it is now 17) and add `KNOWN_UNMAPPABLE_ACTION`. Add the `serviceType`/`accessType` fields to the `GapEntry` field list at line 17, noting they are populated only for action gap types.

- [ ] **Step 5: Update `README.md`**

Per the project's standing rule (README is updated as part of the change, not after):

1. **Gap Types table** — add a `KNOWN_UNMAPPABLE_ACTION` row; amend the `UNSUPPORTED_ACTION` row to say it now means *unrecognised* access types only.
2. **Convertibility Classification table** — state that informational gaps (`WILDCARD_PATTERN`, `CANNOT_VALIDATE_S3_LOCATION`, `KNOWN_UNMAPPABLE_ACTION`) do not demote a policy, and that a NOT_CONVERTIBLE policy whose only gaps are informational still reports them as its reason.
3. **Sample Console Output** — paste the real demo output including the new section.
4. **`--output-gaps-path` docs** — document the new object shape with an example, and add an explicit **breaking change** callout for anyone parsing the old bare array.
5. **JSON Report Format** — add `knownUnmappableActions` to the example, and note that this type's `gapReport.summary` count is the number of *distinct* unmappable access types (entries are collapsed), with exact occurrence counts in the rollup.
6. **Considerations and Limitations** — add a row explaining that Hive `index`/`lock` and Presto/Trino `grant`/`revoke` are dropped by design.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/design/05-ranger-services.md docs/design/09-models-and-reporting.md demo/
git commit -m "docs: document KNOWN_UNMAPPABLE_ACTION gap type and new gaps file format"
```

---

## Verification Checklist

Before declaring this done, confirm each with actual command output — not by inspection:

- [ ] `mvn -q test` fully green.
- [ ] A policy with 3 principals and an unmappable access type reports that finding **once**, and two separate policies with the same unmappable type report **twice**.
- [ ] Calling `convert()` twice reports the gap both times (no dedup state leaking across cycles).
- [ ] A Hive policy with `select` + `index` is reported **fully convertible**.
- [ ] A Hive policy with only `index` + `lock` is **not convertible** *and* its gaps file entry carries a `KNOWN_UNMAPPABLE_ACTION` reason.
- [ ] A policy with a genuinely unrecognised access type is still **partially/not convertible** with an `UNSUPPORTED_ACTION` gap.
- [ ] The assessment JSON contains one `KNOWN_UNMAPPABLE_ACTION` entry per `(serviceType, accessType)`, not per policy.
- [ ] `demo/out/gaps.json` parses as an object with `policies` and `knownUnmappableActions`.
- [ ] `git status` is clean and no unintended files (e.g. `demo/out/`, `.DS_Store`) were committed.

## Follow-up (not this plan)

Moving the aggregation down into `GapReporter` so the live sync server benefits and the >1M-entry list is never built in memory. Recorded in project memory under `future_ideas.md`. Write the merge logic in Task 5 so it is liftable rather than welded to `AssessmentRunner`.
