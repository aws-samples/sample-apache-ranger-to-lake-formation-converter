# Passthrough Principal Mapper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-detect when no principal mapping is configured in the assessment tool and substitute a `PassthroughPrincipalMapper` that echoes Ranger names as `ranger-user:alice` / `ranger-group:analysts` / `ranger-role:admin` placeholders, so first-run customers get meaningful convertibility percentages with a clear warning to configure mappings later.

**Architecture:** New `PassthroughPrincipalMapper` in the `lakeformation` package. `AssessmentRunner.run()` checks whether the config has an empty default mapping and uses the passthrough mapper instead of calling `PrincipalMapperFactory`. Warning strings are collected in a `List<String>` and threaded through `AssessmentResult` (new field at position 9) to `AssessmentReporter`, which prints a `⚠` banner before the report header.

**Tech Stack:** Java 17, JUnit 5, Jackson (`@JsonCreator`, `@JsonProperty`, `@JsonInclude`), Maven (`mvn test`)

---

## File Map

| File | Action |
|---|---|
| `src/main/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapper.java` | **Create** |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java` | **Modify** — add `warnings` field (position 9) |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java` | **Modify** — add `isDefaultEmptyMapping()`, activate passthrough, thread `warnings` to result |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java` | **Modify** — print `⚠` banner before report header |
| `src/test/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapperTest.java` | **Create** |
| `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentResultTest.java` | **Create** — JSON round-trip tests |
| `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java` | **Modify** — two new tests |
| `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java` | **Modify** — update `buildResult` helper + two new tests |
| `README.md` | **Modify** — document passthrough behaviour |

---

### Task 1: `PassthroughPrincipalMapper`

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapper.java`
- Create: `src/test/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapperTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapperTest.java`:

```java
package com.amazonaws.policyconverters.lakeformation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PassthroughPrincipalMapperTest {

    private final PassthroughPrincipalMapper mapper = new PassthroughPrincipalMapper();

    @Test
    void resolveUser_returnsRangerUserPrefix() {
        assertEquals("ranger-user:alice", mapper.resolveUser("alice").orElseThrow());
    }

    @Test
    void resolveGroup_returnsRangerGroupPrefix() {
        assertEquals("ranger-group:analysts", mapper.resolveGroup("analysts").orElseThrow());
    }

    @Test
    void resolveRole_returnsRangerRolePrefix() {
        assertEquals("ranger-role:admin", mapper.resolveRole("admin").orElseThrow());
    }

    @Test
    void resolveUser_neverReturnsEmpty() {
        assertTrue(mapper.resolveUser("any-user").isPresent());
    }

    @Test
    void resolveGroup_neverReturnsEmpty() {
        assertTrue(mapper.resolveGroup("any-group").isPresent());
    }

    @Test
    void resolveRole_neverReturnsEmpty() {
        assertTrue(mapper.resolveRole("any-role").isPresent());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=PassthroughPrincipalMapperTest -q 2>&1 | tail -10
```

Expected: compilation error — `PassthroughPrincipalMapper` does not exist yet.

- [ ] **Step 3: Create `PassthroughPrincipalMapper`**

Create `src/main/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapper.java`:

```java
package com.amazonaws.policyconverters.lakeformation;

import java.util.Optional;

public class PassthroughPrincipalMapper implements PrincipalMapper {

    @Override
    public Optional<String> resolveUser(String name) {
        return Optional.of("ranger-user:" + name);
    }

    @Override
    public Optional<String> resolveGroup(String name) {
        return Optional.of("ranger-group:" + name);
    }

    @Override
    public Optional<String> resolveRole(String name) {
        return Optional.of("ranger-role:" + name);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl . -Dtest=PassthroughPrincipalMapperTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapper.java \
        src/test/java/com/amazonaws/policyconverters/lakeformation/PassthroughPrincipalMapperTest.java
git commit -m "feat: add PassthroughPrincipalMapper for assessment first-run"
```

---

### Task 2: `AssessmentResult.warnings` field

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java`
- Create: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentResultTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentResultTest.java`:

```java
package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void getWarnings_nullConstructorArg_returnsEmptyList() {
        AssessmentResult result = result(null);
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void getWarnings_emptyList_returnsEmptyList() {
        AssessmentResult result = result(List.of());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void serialise_withWarnings_includesWarningsKey() throws Exception {
        AssessmentResult result = result(List.of("test warning"));
        String json = MAPPER.writeValueAsString(result);
        assertTrue(json.contains("\"warnings\""), "warnings key must appear in JSON when non-empty");
        assertTrue(json.contains("test warning"));
    }

    @Test
    void serialise_emptyWarnings_omitsWarningsKey() throws Exception {
        AssessmentResult result = result(List.of());
        String json = MAPPER.writeValueAsString(result);
        assertFalse(json.contains("\"warnings\""), "warnings key must be absent from JSON when empty");
    }

    @Test
    void deserialise_oldJsonWithoutWarnings_returnsEmptyList() throws Exception {
        String oldJson = "{\"totalPolicies\":0,\"fullyConvertible\":0,\"partiallyConvertible\":0,"
                + "\"notConvertible\":0,\"projectedGrantCount\":0,"
                + "\"gapReport\":{\"entries\":[],\"summary\":{},\"generatedAt\":\"2024-01-01T00:00:00Z\"},"
                + "\"source\":\"test\",\"services\":[]}";
        AssessmentResult result = MAPPER.readValue(oldJson, AssessmentResult.class);
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().isEmpty(), "Old JSON without warnings field must deserialise to empty list");
    }

    // ---- helper ----

    private AssessmentResult result(List<String> warnings) {
        GapReport gapReport = new GapReport(
                Collections.emptyList(),
                Collections.emptyMap(),
                "2024-01-01T00:00:00Z");
        return new AssessmentResult(0, 0, 0, 0, 0, gapReport, "test", List.of(), warnings);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=AssessmentResultTest -q 2>&1 | tail -10
```

Expected: compilation error — 9-arg `AssessmentResult` constructor does not exist yet.

- [ ] **Step 3: Add `warnings` field to `AssessmentResult`**

Edit `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java`:

1. Add import at top (after existing imports):
```java
import com.fasterxml.jackson.annotation.JsonInclude;
```

2. Add field (after `private final GapReport gapReport;`):
```java
private final List<String> warnings;
```

3. Replace the `@JsonCreator` constructor with this 9-arg version:
```java
@JsonCreator
public AssessmentResult(
        @JsonProperty("totalPolicies") int totalPolicies,
        @JsonProperty("fullyConvertible") int fullyConvertible,
        @JsonProperty("partiallyConvertible") int partiallyConvertible,
        @JsonProperty("notConvertible") int notConvertible,
        @JsonProperty("projectedGrantCount") int projectedGrantCount,
        @JsonProperty("gapReport") GapReport gapReport,
        @JsonProperty("source") String source,
        @JsonProperty("services") List<AssessedService> services,
        @JsonProperty("warnings") List<String> warnings) {
    this.totalPolicies = totalPolicies;
    this.fullyConvertible = fullyConvertible;
    this.partiallyConvertible = partiallyConvertible;
    this.notConvertible = notConvertible;
    this.projectedGrantCount = projectedGrantCount;
    this.gapReport = gapReport;
    this.source = source;
    this.services = services != null ? Collections.unmodifiableList(services) : Collections.emptyList();
    this.warnings = warnings != null ? Collections.unmodifiableList(warnings) : Collections.emptyList();
}
```

4. Add getter after `getGapReport()`:
```java
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public List<String> getWarnings() { return warnings; }
```

- [ ] **Step 4: Fix the existing call site in `AssessmentRunner.java`**

In `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java`, the `return new AssessmentResult(...)` at lines 169-178 currently passes 8 arguments. Append `Collections.emptyList()` as the 9th:

```java
return new AssessmentResult(
        allPolicies.size(),
        counts[0],
        counts[1],
        counts[2],
        ops.size(),
        gapReport,
        source.sourceLabel(),
        assessedServices,
        Collections.emptyList());
```

Also fix `AssessmentReporterTest.buildResult(...)` (9th arg will be needed — do it now to unblock compilation):

In `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java`, update the `buildResult` helper signature and body, and all 5 call sites:

Helper signature change:
```java
private AssessmentResult buildResult(int total, int fully, int partial, int notConv,
                                     int grants, List<GapEntry> entries,
                                     String source, List<AssessedService> services,
                                     List<String> warnings) {
    GapReport gapReport = new GapReport(entries, GapReport.computeSummary(entries), "2024-01-01T00:00:00Z");
    String resolvedSource = source != null ? source : "ranger-admin:http://localhost:6080";
    List<AssessedService> resolvedServices = services != null ? services : List.of();
    return new AssessmentResult(total, fully, partial, notConv, grants, gapReport,
            resolvedSource, resolvedServices, warnings != null ? warnings : List.of());
}
```

All 5 existing call sites: add `List.of()` as the final argument. For example:
```java
// Before:
buildResult(10, 7, 2, 1, 25, List.of(), null, List.of())
// After:
buildResult(10, 7, 2, 1, 25, List.of(), null, List.of(), List.of())
```
Apply this pattern to all 5 call sites in the file.

- [ ] **Step 5: Run all tests to verify they pass**

```bash
mvn test -pl . -Dtest="AssessmentResultTest,AssessmentReporterTest" -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java \
        src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentResultTest.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java
git commit -m "feat: add warnings field to AssessmentResult"
```

---

### Task 3: Passthrough activation in `AssessmentRunner`

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java`

- [ ] **Step 1: Write the failing tests**

Add two tests at the end of `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java` (before the closing `}`):

```java
@Test
void run_withDefaultEmptyMapping_usesPassthroughAndWarns() {
    // Config with no principal mapping — builder default is empty STATIC
    AssessmentConfig config = AssessmentConfig.builder().consoleOnly(true).build();

    RangerPolicy policy = buildLakeFormationPolicy(99L, "db1", "table1");
    RangerPolicyItem item = new RangerPolicyItem();
    item.setAccesses(List.of(access("select")));
    item.setUsers(List.of("alice"));  // unmapped — passthrough produces ranger-user:alice
    policy.setPolicyItems(List.of(item));

    PolicySource source = stubSource("lakeformation", "lakeformation", List.of(policy));
    AssessmentResult result = new AssessmentRunner().run(config, source);

    assertEquals(1, result.getWarnings().size(), "Expected exactly one passthrough warning");
    assertTrue(result.getWarnings().get(0).contains("No principal mapping"),
            "Warning must mention missing principal mapping");
    assertEquals(1, result.getTotalPolicies());
    // passthrough resolved the principal so at least one op was produced
    assertTrue(result.getFullyConvertible() + result.getPartiallyConvertible() > 0,
            "Passthrough must produce at least one convertible policy");
}

@Test
void run_withNonEmptyStaticMapping_noWarning() {
    // minimalConfig() has alice -> arn:... entry — not empty, must NOT use passthrough
    AssessmentResult result = new AssessmentRunner().run(minimalConfig(),
            stubSource("lakeformation", "lakeformation", List.of()));
    assertTrue(result.getWarnings().isEmpty(), "No warning expected when principal mapping is configured");
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=AssessmentRunnerTest -q 2>&1 | tail -10
```

Expected: both new tests FAIL — `run_withDefaultEmptyMapping_usesPassthroughAndWarns` fails because warnings list is empty (passthrough not activated yet), `run_withNonEmptyStaticMapping_noWarning` passes trivially but verify.

- [ ] **Step 3: Add `isDefaultEmptyMapping` helper and activate passthrough in `AssessmentRunner`**

In `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java`:

1. Add two imports at the top (after existing imports):
```java
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.PassthroughPrincipalMapper;
```

2. Replace the block starting at `// Build IdentitystoreClient only when needed` through `PrincipalMapper principalMapper = PrincipalMapperFactory.create(...)` (currently lines 103-119) with:

```java
List<String> warnings = new ArrayList<>();
// Build PrincipalMapper — use passthrough when no mapping is configured
IdentitystoreClient identityStoreClient = null;
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
    if (principalMappingConfig != null
            && principalMappingConfig.getType() == PrincipalMapperType.IDENTITY_CENTER) {
        identityStoreClient = config.getAwsConfig().map(awsConfig -> {
            software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials =
                    ConversionServerMain.buildCredentialsProvider(awsConfig);
            return IdentitystoreClient.builder()
                    .region(Region.of(principalMappingConfig.getIdcConfig().getRegion()))
                    .credentialsProvider(credentials)
                    .build();
        }).orElse(null);
    }
    principalMapper = PrincipalMapperFactory.create(principalMappingConfig, identityStoreClient, null);
}
```

3. Replace the `return new AssessmentResult(...)` at the end of `run()` — change the last arg from `Collections.emptyList()` to `warnings`:

```java
return new AssessmentResult(
        allPolicies.size(),
        counts[0],
        counts[1],
        counts[2],
        ops.size(),
        gapReport,
        source.sourceLabel(),
        assessedServices,
        warnings);
```

4. Add the private helper method at the end of `AssessmentRunner` (before the final `}`):

```java
private static boolean isDefaultEmptyMapping(PrincipalMappingConfig cfg) {
    return cfg != null
            && cfg.getType() == PrincipalMapperType.STATIC
            && cfg.getUserMappings().isEmpty()
            && cfg.getGroupMappings().isEmpty()
            && cfg.getRoleMappings().isEmpty()
            && cfg.getDelegates().isEmpty();
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl . -Dtest=AssessmentRunnerTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS` (all 10 tests pass)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java
git commit -m "feat: activate PassthroughPrincipalMapper when no mapping configured"
```

---

### Task 4: Warning banner in `AssessmentReporter`

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java`

- [ ] **Step 1: Write the failing tests**

Add two tests to `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java` (before the `// ---- helpers ----` comment):

```java
@Test
void report_withWarnings_printsBannerBeforeHeader() {
    AssessmentResult result = buildResult(5, 5, 0, 0, 10, List.of(), null, List.of(),
            List.of("No principal mapping configured. Ranger usernames are passed through as-is."));
    AssessmentConfig config = configConsoleOnly();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    new AssessmentReporter().report(result, config, new PrintStream(baos));
    String output = baos.toString();

    int warningIdx = output.indexOf("⚠");
    int headerIdx = output.indexOf("=== Apache Ranger");
    assertTrue(warningIdx >= 0, "Warning banner (⚠) must appear in output");
    assertTrue(warningIdx < headerIdx, "Warning banner must appear before the report header");
}

@Test
void report_withoutWarnings_noBannerInOutput() {
    AssessmentResult result = buildResult(5, 5, 0, 0, 10, List.of(), null, List.of(), List.of());
    AssessmentConfig config = configConsoleOnly();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    new AssessmentReporter().report(result, config, new PrintStream(baos));
    String output = baos.toString();

    assertFalse(output.contains("⚠"), "No warning banner expected when warnings list is empty");
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=AssessmentReporterTest -q 2>&1 | tail -10
```

Expected: `report_withWarnings_printsBannerBeforeHeader` FAILS — no `⚠` in output yet. `report_withoutWarnings_noBannerInOutput` passes trivially.

- [ ] **Step 3: Add warning banner to `AssessmentReporter.printConsoleReport()`**

In `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java`, find `printConsoleReport`. Insert the warning banner block **before** the first `out.println()` line:

```java
// Warning banner — printed before the report header
for (String warning : result.getWarnings()) {
    out.println("⚠  " + warning);
    out.println();
}
```

So the start of the method body becomes:

```java
private void printConsoleReport(AssessmentResult result, PrintStream out) {
    int total = result.getTotalPolicies();
    Map<GapEntry.GapType, Integer> summary = result.getGapReport().getSummary();
    int totalGaps = summary.values().stream().mapToInt(Integer::intValue).sum();

    // Warning banner — printed before the report header
    for (String warning : result.getWarnings()) {
        out.println("⚠  " + warning);
        out.println();
    }

    out.println();
    out.println("=== Apache Ranger → Lake Formation Assessment ===");
    // ... rest unchanged ...
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl . -Dtest=AssessmentReporterTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Run full test suite**

```bash
mvn test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS` with all tests passing.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java
git commit -m "feat: print passthrough warning banner in assessment console output"
```

---

### Task 5: README update

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add passthrough mapper documentation**

In `README.md`, find the section describing the `assess file` command usage (the section that shows the console output sample). Make the following additions:

**a) Add a "Principal Mapping" subsection** after the "Obtaining a Ranger Export File" subsection (or equivalent location in the assess section):

```markdown
#### Principal Mapping in Assessment Mode

When you run `assess file` without a config file, the tool has no information about how
Ranger usernames map to IAM ARNs. In this case it automatically uses a **passthrough mapper**
that echoes Ranger names as placeholder identifiers:

| Ranger principal | Placeholder in report |
|---|---|
| user `alice` | `ranger-user:alice` |
| group `analysts` | `ranger-group:analysts` |
| role `admin` | `ranger-role:admin` |

This lets the tool measure structural convertibility (which policies have gaps) without
requiring IAM configuration upfront. A warning banner is printed at the top of the report:

```
⚠  No principal mapping configured. Ranger usernames are passed through as-is
   (e.g. "ranger-user:alice", "ranger-group:analysts"). Re-run with a config file
   that includes a principalMapping section to produce accurate LF grant output.
```

Once you know which policies are convertible, add a `principalMapping` section to your
config file and re-run with `assess server` to generate accurate LF grant output.
```

**b) Add `warnings` to the JSON report format sample** (find the JSON example in the README that shows `"source"` and `"services"` and add `"warnings"` after `"services"`):

```json
  "warnings": [
    "No principal mapping configured. Ranger usernames are passed through as-is ..."
  ],
```

Note: `warnings` is omitted from the JSON entirely when the list is empty (configured runs are unaffected).

- [ ] **Step 2: Verify README renders correctly**

Read through the changed sections to confirm formatting looks correct (no broken markdown).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document passthrough principal mapper in assessment README"
```

---

## Final Verification

- [ ] **Run the full test suite one last time**

```bash
mvn test -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Smoke test against the real export file**

```bash
java -jar target/assessment-jar-with-dependencies.jar file ./Ranger_Policies_20260602_001709.json --console-only 2>/dev/null | head -20
```

Expected output starts with:
```
⚠  No principal mapping configured. Ranger usernames are passed through as-is ...

=== Apache Ranger → Lake Formation Assessment ===
Source:       file:Ranger_Policies_20260602_001709.json
```

And the convertibility percentages should now be non-zero (policies with user items will resolve via passthrough).
