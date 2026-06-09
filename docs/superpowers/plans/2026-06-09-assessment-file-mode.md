# Assessment File Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `assess file <export.json>` subcommand to the assessment CLI that reads a Ranger Admin export JSON instead of connecting to a live Ranger Admin server.

**Architecture:** Introduce a `PolicySource` interface with two implementations (`RangerAdminPolicySource` and `RangerExportFilePolicySource`). `AssessmentRunner.run()` accepts a `PolicySource` instead of fetching policies internally. `AssessmentMain` dispatches on `args[0]` (`"server"` or `"file"`) to build the right source and pass it through. Output is enriched with `source` and `services` metadata in both console and JSON formats.

**Tech Stack:** Java 17, JUnit 5, Jackson (already on classpath), Apache Ranger 2.4 (`RangerPolicy` model), Maven (`mvn test` to run tests)

---

## File Map

| Path | Action | Responsibility |
|---|---|---|
| `src/main/java/com/amazonaws/policyconverters/assessment/PolicySource.java` | Create | Interface: `load()` + `sourceLabel()` |
| `src/main/java/com/amazonaws/policyconverters/assessment/ServicePolicyBatch.java` | Create | Value object: per-service batch of policies + metadata |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessedService.java` | Create | JSON value object for per-service result in `AssessmentResult` |
| `src/main/java/com/amazonaws/policyconverters/assessment/RangerExportModel.java` | Create | Package-private Jackson deserialization model for export JSON |
| `src/main/java/com/amazonaws/policyconverters/assessment/RangerAdminPolicySource.java` | Create | `PolicySource` impl wrapping existing HTTP fetch |
| `src/main/java/com/amazonaws/policyconverters/assessment/RangerExportFilePolicySource.java` | Create | `PolicySource` impl reading Ranger export JSON |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentConfig.java` | Modify | Remove `rangerAdminUrl` required-check from `build()` (line 140) |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java` | Modify | Add `source` and `services` fields to `@JsonCreator` constructor |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java` | Modify | Accept `PolicySource`, remove `fetchPolicies` and `buildAdapterRegistry` |
| `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java` | Modify | Always render source/services preamble |
| `src/main/java/com/amazonaws/policyconverters/app/AssessmentMain.java` | Modify | Subcommand dispatch, new `USAGE`, `parseServerArgs`, `parseFileArgs` |
| `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java` | Modify | Rewrite 6 tests to use `PolicySource` stubs |
| `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java` | Modify | Update `buildResult` helper to pass `source`/`services`; fix `configConsoleOnly` (no longer needs `rangerAdminUrl`) |
| `src/test/java/com/amazonaws/policyconverters/assessment/RangerExportFilePolicySourceTest.java` | Create | Unit tests for file parsing, filtering, skipping |
| `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentMainTest.java` | Create | Subcommand dispatch tests |
| `README.md` | Modify | Update Pre-Migration Assessment Tool section |

---

## Task 1: `PolicySource` interface and `ServicePolicyBatch` value object

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/PolicySource.java`
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/ServicePolicyBatch.java`

These have no dependencies on other new classes. No test needed — they are data types used by the rest.

- [ ] **Step 1: Create `PolicySource.java`**

```java
package com.amazonaws.policyconverters.assessment;

import java.util.List;

public interface PolicySource {
    List<ServicePolicyBatch> load();
    String sourceLabel();
}
```

- [ ] **Step 2: Create `ServicePolicyBatch.java`**

```java
package com.amazonaws.policyconverters.assessment;

import org.apache.ranger.plugin.model.RangerPolicy;

import java.util.Collections;
import java.util.List;

public class ServicePolicyBatch {

    private final String serviceName;
    private final String serviceType;
    private final List<RangerPolicy> policies;
    private final int rawPolicyCount;
    private final String skipReason;

    private ServicePolicyBatch(String serviceName, String serviceType,
                                List<RangerPolicy> policies, int rawPolicyCount,
                                String skipReason) {
        this.serviceName = serviceName;
        this.serviceType = serviceType;
        this.policies = Collections.unmodifiableList(policies);
        this.rawPolicyCount = rawPolicyCount;
        this.skipReason = skipReason;
    }

    public static ServicePolicyBatch assessed(String serviceName, String serviceType,
                                               List<RangerPolicy> policies, int rawPolicyCount) {
        return new ServicePolicyBatch(serviceName, serviceType, policies, rawPolicyCount, null);
    }

    public static ServicePolicyBatch skipped(String serviceName, String serviceType,
                                              int rawPolicyCount, String skipReason) {
        return new ServicePolicyBatch(serviceName, serviceType,
                Collections.emptyList(), rawPolicyCount, skipReason);
    }

    public String getServiceName()    { return serviceName; }
    public String getServiceType()    { return serviceType; }
    public List<RangerPolicy> getPolicies() { return policies; }
    public int getRawPolicyCount()    { return rawPolicyCount; }
    public String getSkipReason()     { return skipReason; }
    public boolean isSkipped()        { return skipReason != null; }
}
```

- [ ] **Step 3: Verify compile**

```bash
cd /path/to/ApacheRangerToLF && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/PolicySource.java \
        src/main/java/com/amazonaws/policyconverters/assessment/ServicePolicyBatch.java
git commit -m "feat: add PolicySource interface and ServicePolicyBatch value object"
```

---

## Task 2: `AssessedService` and `RangerExportModel`

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/AssessedService.java`
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/RangerExportModel.java`

- [ ] **Step 1: Create `AssessedService.java`**

```java
package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssessedService {

    private final String name;
    private final String serviceType;
    private final String status;
    private final int policiesScanned;
    private final String skipReason;

    @JsonCreator
    public AssessedService(
            @JsonProperty("name") String name,
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("status") String status,
            @JsonProperty("policiesScanned") int policiesScanned,
            @JsonProperty("skipReason") String skipReason) {
        this.name = name;
        this.serviceType = serviceType;
        this.status = status;
        this.policiesScanned = policiesScanned;
        this.skipReason = skipReason;
    }

    public static AssessedService assessed(String name, String serviceType, int policiesScanned) {
        return new AssessedService(name, serviceType, "assessed", policiesScanned, null);
    }

    public static AssessedService skipped(String name, String serviceType, String skipReason) {
        return new AssessedService(name, serviceType, "skipped", 0, skipReason);
    }

    public String getName()           { return name; }
    public String getServiceType()    { return serviceType; }
    public String getStatus()         { return status; }
    public int getPoliciesScanned()   { return policiesScanned; }
    public String getSkipReason()     { return skipReason; }
}
```

- [ ] **Step 2: Create `RangerExportModel.java`**

```java
package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.ranger.plugin.model.RangerPolicy;

import java.util.List;

class RangerExportModel {

    @JsonProperty("policies")
    List<RangerPolicy> policies;
}
```

- [ ] **Step 3: Verify compile**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessedService.java \
        src/main/java/com/amazonaws/policyconverters/assessment/RangerExportModel.java
git commit -m "feat: add AssessedService value object and RangerExportModel"
```

---

## Task 3: `AssessmentConfig` — remove `rangerAdminUrl` required-check

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentConfig.java:139-143`

- [ ] **Step 1: Write a failing test confirming `build()` works without `rangerAdminUrl`**

Add to `AssessmentReporterTest.java` (it already imports `AssessmentConfig`):

```java
@Test
void assessmentConfig_buildsWithoutRangerAdminUrl() {
    // Should not throw — validation moves to AssessmentMain per subcommand
    AssessmentConfig config = AssessmentConfig.builder()
            .consoleOnly(true)
            .build();
    assertNotNull(config);
}
```

- [ ] **Step 2: Run test to see it fail**

```bash
mvn test -pl . -Dtest=AssessmentReporterTest#assessmentConfig_buildsWithoutRangerAdminUrl -q
```

Expected: FAIL — `IllegalStateException: rangerAdminUrl is required`

- [ ] **Step 3: Remove the guard from `AssessmentConfig.Builder.build()` (line 140-142)**

Delete these lines from `AssessmentConfig.java`:
```java
if (rangerAdminUrl == null || rangerAdminUrl.isBlank()) {
    throw new IllegalStateException("rangerAdminUrl is required");
}
```

- [ ] **Step 4: Run test to verify it passes, then run full suite**

```bash
mvn test -pl . -Dtest=AssessmentReporterTest -q
mvn test -q
```

Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentConfig.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java
git commit -m "feat: remove rangerAdminUrl required-check from AssessmentConfig.build()"
```

---

## Task 4: `AssessmentResult` — add `source` and `services` fields

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java`

- [ ] **Step 1: Update `AssessmentReporterTest` helper to pass the new fields**

In `AssessmentReporterTest.java`, update `buildResult`:

```java
private AssessmentResult buildResult(int total, int fully, int partial, int notConv,
                                     int grants, List<GapEntry> entries) {
    GapReport gapReport = new GapReport(entries, GapReport.computeSummary(entries), "2024-01-01T00:00:00Z");
    return new AssessmentResult(total, fully, partial, notConv, grants, gapReport,
            "ranger-admin:http://localhost:6080", List.of());
}
```

- [ ] **Step 2: Run to confirm it fails (constructor signature mismatch)**

```bash
mvn test -pl . -Dtest=AssessmentReporterTest -q
```

Expected: compile error — `AssessmentResult` constructor does not match

- [ ] **Step 3: Update `AssessmentResult.java` — add `source` and `services` to constructor**

Replace the existing class body with:

```java
package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapReport;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

public class AssessmentResult {

    private final String source;
    private final List<AssessedService> services;
    private final int totalPolicies;
    private final int fullyConvertible;
    private final int partiallyConvertible;
    private final int notConvertible;
    private final int projectedGrantCount;
    private final GapReport gapReport;

    @JsonCreator
    public AssessmentResult(
            @JsonProperty("totalPolicies") int totalPolicies,
            @JsonProperty("fullyConvertible") int fullyConvertible,
            @JsonProperty("partiallyConvertible") int partiallyConvertible,
            @JsonProperty("notConvertible") int notConvertible,
            @JsonProperty("projectedGrantCount") int projectedGrantCount,
            @JsonProperty("gapReport") GapReport gapReport,
            @JsonProperty("source") String source,
            @JsonProperty("services") List<AssessedService> services) {
        this.totalPolicies = totalPolicies;
        this.fullyConvertible = fullyConvertible;
        this.partiallyConvertible = partiallyConvertible;
        this.notConvertible = notConvertible;
        this.projectedGrantCount = projectedGrantCount;
        this.gapReport = gapReport;
        this.source = source;
        this.services = services != null ? Collections.unmodifiableList(services) : Collections.emptyList();
    }

    public String getSource()                    { return source; }
    public List<AssessedService> getServices()   { return services; }
    public int getTotalPolicies()                { return totalPolicies; }
    public int getFullyConvertible()             { return fullyConvertible; }
    public int getPartiallyConvertible()         { return partiallyConvertible; }
    public int getNotConvertible()               { return notConvertible; }
    public int getProjectedGrantCount()          { return projectedGrantCount; }
    public GapReport getGapReport()              { return gapReport; }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -q
```

Expected: all pass (existing callers pass `null` for `source`/`services` implicitly — but note the new constructor places `source` and `services` at positions 7 and 8, so any other call sites creating `AssessmentResult` with 6 args will fail to compile. Check for and fix any such call sites now.)

```bash
grep -rn "new AssessmentResult(" src/
```

Update any remaining 6-arg calls to add `null, Collections.emptyList()` as the last two args.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentResult.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java
git commit -m "feat: add source and services fields to AssessmentResult"
```

---

## Task 5: `RangerAdminPolicySource`

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/RangerAdminPolicySource.java`

No unit test needed here — this class wraps a static HTTP call to a live server; it is covered end-to-end by the existing integration tests and by the `AssessmentRunnerTest` migration in Task 7.

- [ ] **Step 1: Create `RangerAdminPolicySource.java`**

```java
package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.app.ConversionServerMain;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RangerAdminPolicySource implements PolicySource {

    private static final Logger LOG = LoggerFactory.getLogger(RangerAdminPolicySource.class);

    private final String rangerAdminUrl;
    private final String username;
    private final String password;
    private final List<RangerServiceConfig> services;

    public RangerAdminPolicySource(String rangerAdminUrl, String username, String password,
                                    List<RangerServiceConfig> services) {
        if (rangerAdminUrl == null || rangerAdminUrl.isBlank()) {
            throw new IllegalArgumentException("rangerAdminUrl must not be blank");
        }
        this.rangerAdminUrl = rangerAdminUrl;
        this.username = username;
        this.password = password;
        this.services = services != null ? services : List.of();
    }

    @Override
    public String sourceLabel() {
        return "ranger-admin:" + rangerAdminUrl;
    }

    @Override
    public List<ServicePolicyBatch> load() {
        List<ServicePolicyBatch> batches = new ArrayList<>();
        List<RangerServiceConfig> toFetch = services.isEmpty()
                ? List.of(new RangerServiceConfig("lakeformation", "lakeformation", null, null))
                : services;

        for (RangerServiceConfig svc : toFetch) {
            String instanceName = svc.getServiceInstanceName();
            ServicePolicies sp = ConversionServerMain.fetchPoliciesFromRangerAdmin(
                    rangerAdminUrl, username, password, instanceName);

            if (sp == null || sp.getPolicies() == null) {
                LOG.warn("No policies returned from Ranger Admin for service '{}'", instanceName);
                batches.add(ServicePolicyBatch.skipped(
                        instanceName, svc.getServiceType(), 0,
                        "fetch failed or returned no data"));
            } else {
                List<RangerPolicy> policies = sp.getPolicies();
                // fetchPoliciesFromRangerAdmin already filters disabled policies;
                // rawPolicyCount == policies.size() here (post-filter)
                batches.add(ServicePolicyBatch.assessed(
                        instanceName, svc.getServiceType(), policies, policies.size()));
            }
        }
        return batches;
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/RangerAdminPolicySource.java
git commit -m "feat: add RangerAdminPolicySource wrapping Ranger Admin HTTP fetch"
```

---

## Task 6: `RangerExportFilePolicySource` (with tests)

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/assessment/RangerExportFilePolicySource.java`
- Create: `src/test/java/com/amazonaws/policyconverters/assessment/RangerExportFilePolicySourceTest.java`

The known service types must match `ConversionServerMain.createRangerService()`: `lakeformation`, `hive`, `presto`, `trino`, `amazon-emr-emrfs`.

- [ ] **Step 1: Write the tests first**

Create `src/test/java/com/amazonaws/policyconverters/assessment/RangerExportFilePolicySourceTest.java`:

```java
package com.amazonaws.policyconverters.assessment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RangerExportFilePolicySourceTest {

    @TempDir
    Path tempDir;

    @Test
    void load_wellFormedExport_assessesKnownServicesAndSkipsUnknown() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":"hive_prod","serviceType":"hive","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":2,"name":"p2","service":"lf_prod","serviceType":"lakeformation","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":3,"name":"p3","service":"yarn_prod","serviceType":"yarn","isEnabled":true,
                   "resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();

        assertEquals(3, batches.size());
        ServicePolicyBatch hive = batch(batches, "hive_prod");
        assertFalse(hive.isSkipped());
        assertEquals(1, hive.getPolicies().size());

        ServicePolicyBatch lf = batch(batches, "lf_prod");
        assertFalse(lf.isSkipped());
        assertEquals(1, lf.getPolicies().size());

        ServicePolicyBatch yarn = batch(batches, "yarn_prod");
        assertTrue(yarn.isSkipped());
        assertEquals(1, yarn.getRawPolicyCount());
        assertNotNull(yarn.getSkipReason());
    }

    @Test
    void load_disabledPoliciesFilteredFromPoliciesButCountedInRaw() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":"lf_prod","serviceType":"lakeformation","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":2,"name":"p2","service":"lf_prod","serviceType":"lakeformation","isEnabled":false,
                   "resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();

        assertEquals(1, batches.size());
        ServicePolicyBatch batch = batches.get(0);
        assertFalse(batch.isSkipped());
        assertEquals(1, batch.getPolicies().size(), "disabled policy must be excluded");
        assertEquals(2, batch.getRawPolicyCount(), "rawPolicyCount must include disabled policy");
    }

    @Test
    void load_emptyPoliciesArray_returnsEmptyBatches() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertTrue(batches.isEmpty());
    }

    @Test
    void load_missingPoliciesKey_returnsEmptyBatches() throws IOException {
        Path file = writeExport(tempDir, """
                {}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertTrue(batches.isEmpty());
    }

    @Test
    void load_policyWithNullService_isSkippedIndividually() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":null,"serviceType":"hive","isEnabled":true,
                   "resources":{},"policyItems":[]},
                  {"id":2,"name":"p2","service":"hive_prod","serviceType":"hive","isEnabled":true,
                   "resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertEquals(1, batches.size());
        assertEquals("hive_prod", batches.get(0).getServiceName());
        assertEquals(1, batches.get(0).getPolicies().size());
    }

    @Test
    void load_emrfsServiceType_isAssessed() throws IOException {
        Path file = writeExport(tempDir, """
                {"policies":[
                  {"id":1,"name":"p1","service":"emrfs_prod","serviceType":"amazon-emr-emrfs",
                   "isEnabled":true,"resources":{},"policyItems":[]}
                ]}""");

        List<ServicePolicyBatch> batches = new RangerExportFilePolicySource(file).load();
        assertEquals(1, batches.size());
        assertFalse(batches.get(0).isSkipped(), "amazon-emr-emrfs must be assessed, not skipped");
    }

    @Test
    void load_sourceLabel_returnsFilenameOnly() throws IOException {
        Path file = writeExport(tempDir, "{\"policies\":[]}");
        String label = new RangerExportFilePolicySource(file).sourceLabel();
        assertEquals("file:" + file.getFileName().toString(), label);
    }

    // ---- helpers ----

    private Path writeExport(Path dir, String json) throws IOException {
        Path file = dir.resolve("export.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private ServicePolicyBatch batch(List<ServicePolicyBatch> batches, String serviceName) {
        return batches.stream()
                .filter(b -> serviceName.equals(b.getServiceName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No batch for service: " + serviceName));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=RangerExportFilePolicySourceTest -q
```

Expected: compile error — class does not exist yet

- [ ] **Step 3: Create `RangerExportFilePolicySource.java`**

```java
package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RangerExportFilePolicySource implements PolicySource {

    private static final Logger LOG = LoggerFactory.getLogger(RangerExportFilePolicySource.class);

    private static final Set<String> KNOWN_SERVICE_TYPES = Set.of(
            "lakeformation", "hive", "presto", "trino", "amazon-emr-emrfs");

    private final Path exportFile;

    public RangerExportFilePolicySource(Path exportFile) {
        this.exportFile = exportFile;
    }

    @Override
    public String sourceLabel() {
        return "file:" + exportFile.getFileName().toString();
    }

    @Override
    public List<ServicePolicyBatch> load() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        RangerExportModel model;
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(exportFile), StandardCharsets.UTF_8)) {
            model = mapper.readValue(reader, RangerExportModel.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Ranger export file: " + exportFile, e);
        }

        if (model.policies == null || model.policies.isEmpty()) {
            LOG.warn("Ranger export file '{}' contains no policies", exportFile.getFileName());
            return List.of();
        }

        // Group by service name, tracking raw count before isEnabled filtering
        Map<String, List<RangerPolicy>> allByService = new LinkedHashMap<>();
        Map<String, String> serviceTypeByName = new LinkedHashMap<>();

        for (RangerPolicy policy : model.policies) {
            if (policy.getService() == null || policy.getServiceType() == null) {
                LOG.warn("Skipping policy id={} name='{}': null service or serviceType",
                        policy.getId(), policy.getName());
                continue;
            }
            String svcName = policy.getService();
            allByService.computeIfAbsent(svcName, k -> new ArrayList<>()).add(policy);
            serviceTypeByName.putIfAbsent(svcName, policy.getServiceType());
        }

        List<ServicePolicyBatch> batches = new ArrayList<>();
        for (Map.Entry<String, List<RangerPolicy>> entry : allByService.entrySet()) {
            String svcName = entry.getKey();
            String svcType = serviceTypeByName.get(svcName);
            List<RangerPolicy> all = entry.getValue();
            int rawCount = all.size();

            if (!KNOWN_SERVICE_TYPES.contains(svcType)) {
                LOG.warn("Skipping service '{}' (serviceType='{}'): unsupported service type",
                        svcName, svcType);
                batches.add(ServicePolicyBatch.skipped(svcName, svcType, rawCount,
                        "unsupported service type"));
            } else {
                List<RangerPolicy> enabled = new ArrayList<>(all);
                enabled.removeIf(p -> p.getIsEnabled() != null && !p.getIsEnabled());
                batches.add(ServicePolicyBatch.assessed(svcName, svcType, enabled, rawCount));
            }
        }
        return batches;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl . -Dtest=RangerExportFilePolicySourceTest -q
```

Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/RangerExportFilePolicySource.java \
        src/main/java/com/amazonaws/policyconverters/assessment/RangerExportModel.java \
        src/test/java/com/amazonaws/policyconverters/assessment/RangerExportFilePolicySourceTest.java
git commit -m "feat: add RangerExportFilePolicySource with tests"
```

---

## Task 7: Refactor `AssessmentRunner` to accept `PolicySource`

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java`

The key changes: remove `fetchPolicies` and `buildAdapterRegistry`, accept a `PolicySource`, key the adapter registry on `serviceName` (not `serviceType`).

- [ ] **Step 1: Rewrite `AssessmentRunnerTest` to use `PolicySource` stubs**

Replace the entire file content (keep package/imports, replace class body):

```java
package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.S3AccessGrantsConfig;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.s3accessgrants.OperationType;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantPermission;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClient;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerDataMaskPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssessmentRunnerTest {

    private static final String ACCOUNT_ID = "123456789012";

    @Test
    void run_withEmptyPolicies_returnsZeroCounts() {
        PolicySource source = stubSource("lakeformation", "lakeformation", List.of());
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(0, result.getTotalPolicies());
        assertEquals(0, result.getFullyConvertible());
        assertEquals(0, result.getPartiallyConvertible());
        assertEquals(0, result.getNotConvertible());
        assertEquals(0, result.getProjectedGrantCount());
        assertNotNull(result.getGapReport());
    }

    @Test
    void run_withDataMaskingPolicy_recordsGapAndCountsPartial() {
        RangerPolicy policy = buildLakeFormationPolicy(1L, "db1", "table1");
        policy.setPolicyType(1);
        policy.setDataMaskPolicyItems(List.of(new RangerDataMaskPolicyItem()));

        PolicySource source = stubSource("lakeformation", "lakeformation", List.of(policy));
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getGapReport().getSummary().containsKey(GapType.DATA_MASKING));
    }

    @Test
    void run_withFullyConvertiblePolicy_noGaps() {
        RangerPolicy policy = buildLakeFormationPolicy(2L, "db1", "table1");
        RangerPolicyItem item = new RangerPolicyItem();
        item.setAccesses(List.of(access("select")));
        item.setUsers(List.of("arn:aws:iam::" + ACCOUNT_ID + ":user/alice"));
        policy.setPolicyItems(List.of(item));

        PolicySource source = stubSource("lakeformation", "lakeformation", List.of(policy));
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getProjectedGrantCount() >= 0);
    }

    @Test
    void run_withTagBasedPolicy_countedAsNotConvertible() {
        RangerPolicy policy = buildLakeFormationPolicy(3L, "db1", "table1");
        policy.setService("lakeformation_tag");
        policy.setPolicyItems(List.of());

        PolicySource source = stubSource("lakeformation_tag", "lakeformation", List.of(policy));
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getGapReport().getSummary().getOrDefault(GapType.TAG_BASED_POLICY, 0) > 0
                || result.getNotConvertible() == 1);
    }

    @Test
    void run_withSkippedBatch_recordsUnsupportedServiceTypeGap() {
        PolicySource source = () -> List.of(
                ServicePolicyBatch.skipped("yarn_prod", "yarn", 5, "unsupported service type"));
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(0, result.getTotalPolicies(), "skipped policies must not count toward total");
        assertTrue(result.getGapReport().getSummary().containsKey(GapType.UNSUPPORTED_SERVICE_TYPE));
    }

    @Test
    void run_withUnregisteredS3Location_recordsUnregisteredS3LocationGap() {
        S3AccessGrantsClient mockS3AgClient = mock(S3AccessGrantsClient.class);
        when(mockS3AgClient.listRegisteredLocations()).thenReturn(Set.of("s3://registered-bucket/"));

        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT, "arn:aws:iam::123456789012:user/alice",
                "s3://other-bucket/data/", S3AccessGrantPermission.READ, null);

        PolicySource source = stubSource("emrfs_prod", "amazon-emr-emrfs", List.of());
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet s) {
                return List.of(emrfsOp);
            }
            @Override
            protected S3AccessGrantsClient createS3AccessGrantsClient(S3AccessGrantsConfig c) {
                return mockS3AgClient;
            }
        };
        AssessmentResult result = runner.run(s3AgConfig(), source);

        assertTrue(result.getGapReport().getSummary().containsKey(GapType.UNREGISTERED_S3_LOCATION));
        assertEquals(1, result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNREGISTERED_S3_LOCATION).count());
    }

    @Test
    void run_withoutS3AgConfig_recordsCannotValidateS3LocationGap() {
        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT, "arn:aws:iam::123456789012:user/alice",
                "s3://my-bucket/data/", S3AccessGrantPermission.READ, null);

        PolicySource source = stubSource("lakeformation", "lakeformation", List.of());
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet s) {
                return List.of(emrfsOp);
            }
        };
        AssessmentResult result = runner.run(minimalConfig(), source);

        assertTrue(result.getGapReport().getSummary().containsKey(GapType.CANNOT_VALIDATE_S3_LOCATION));
        assertEquals(1, result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.CANNOT_VALIDATE_S3_LOCATION).count());
    }

    @Test
    void run_withRegisteredS3Location_noUnregisteredGap() {
        S3AccessGrantsClient mockS3AgClient = mock(S3AccessGrantsClient.class);
        when(mockS3AgClient.listRegisteredLocations()).thenReturn(Set.of("s3://my-bucket/"));

        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT, "arn:aws:iam::123456789012:user/alice",
                "s3://my-bucket/data/", S3AccessGrantPermission.READ, null);

        PolicySource source = stubSource("emrfs_prod", "amazon-emr-emrfs", List.of());
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet s) {
                return List.of(emrfsOp);
            }
            @Override
            protected S3AccessGrantsClient createS3AccessGrantsClient(S3AccessGrantsConfig c) {
                return mockS3AgClient;
            }
        };
        AssessmentResult result = runner.run(s3AgConfig(), source);

        assertFalse(result.getGapReport().getSummary().containsKey(GapType.UNREGISTERED_S3_LOCATION));
    }

    // ---- helpers ----

    /** Stub source returning a single assessed batch. serviceName is both the instance
     *  name set on each policy via policy.setService() and the batch name. */
    private PolicySource stubSource(String serviceName, String serviceType, List<RangerPolicy> policies) {
        for (RangerPolicy p : policies) {
            p.setService(serviceName);
        }
        return () -> List.of(ServicePolicyBatch.assessed(serviceName, serviceType,
                policies, policies.size()));
    }

    private AssessmentConfig minimalConfig() {
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("alice", "arn:aws:iam::" + ACCOUNT_ID + ":user/alice");
        return AssessmentConfig.builder()
                .services(List.of(new RangerServiceConfig("lakeformation", "lakeformation", null, null)))
                .principalMapping(new PrincipalMappingConfig(
                        userMappings, Collections.emptyMap(), Collections.emptyMap()))
                .consoleOnly(true)
                .build();
    }

    private AssessmentConfig s3AgConfig() {
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("alice", "arn:aws:iam::" + ACCOUNT_ID + ":user/alice");
        return AssessmentConfig.builder()
                .services(List.of(new RangerServiceConfig("amazon-emr-emrfs", "emrfs_prod", null, null)))
                .principalMapping(new PrincipalMappingConfig(
                        userMappings, Collections.emptyMap(), Collections.emptyMap()))
                .consoleOnly(true)
                .s3AccessGrants(new S3AccessGrantsConfig(
                        "arn:aws:s3:us-east-1:" + ACCOUNT_ID + ":access-grants/default", ACCOUNT_ID))
                .build();
    }

    private RangerPolicy buildLakeFormationPolicy(long id, String db, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("policy-" + id);
        policy.setService("lakeformation");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource(db));
        resources.put("table", new RangerPolicyResource(table));
        policy.setResources(resources);
        return policy;
    }

    private RangerPolicyItemAccess access(String type) {
        RangerPolicyItemAccess a = new RangerPolicyItemAccess();
        a.setType(type);
        a.setIsAllowed(true);
        return a;
    }
}
```

- [ ] **Step 2: Run to confirm tests fail (runner signature unchanged)**

```bash
mvn test -pl . -Dtest=AssessmentRunnerTest -q
```

Expected: compile error — `run(config)` called with two args

- [ ] **Step 3: Refactor `AssessmentRunner.run()` to accept `PolicySource`**

Key logic in `run()` after accepting `PolicySource source`:

```java
public AssessmentResult run(AssessmentConfig config, PolicySource source) {
    List<ServicePolicyBatch> batches = source.load();

    GapReporter gapReporter = new GapReporter();
    // ... (keep existing IdentityStoreClient / PrincipalMapper / CatalogResolver setup unchanged) ...

    // Build adapter registry keyed on serviceName (instance name)
    Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
    for (ServicePolicyBatch batch : batches) {
        if (!batch.isSkipped()) {
            AwsContext awsContext = config.getAwsConfig()
                    .map(aws -> new AwsContext(aws.getRegion(), aws.getCatalogId(), aws.getCatalogId()))
                    .orElse(new AwsContext("us-east-1", "000000000000", "000000000000"));
            BaseRangerService service = ConversionServerMain.createRangerService(
                    new RangerServiceConfig(batch.getServiceType(), batch.getServiceName(), null, null));
            adapterRegistry.put(batch.getServiceName(), service.createAdapter(awsContext));
        }
    }

    // Record UNSUPPORTED_SERVICE_TYPE gap for skipped batches; exclude from policy list
    List<RangerPolicy> allPolicies = new ArrayList<>();
    List<AssessedService> assessedServices = new ArrayList<>();
    for (ServicePolicyBatch batch : batches) {
        if (batch.isSkipped()) {
            gapReporter.recordGap(new GapEntry(
                    null, null,
                    GapEntry.GapType.UNSUPPORTED_SERVICE_TYPE,
                    null,
                    "Service '" + batch.getServiceName() + "' (serviceType='" + batch.getServiceType()
                            + "') has no registered adapter. All " + batch.getRawPolicyCount()
                            + " policies in this service are skipped.",
                    "Supported service types are: lakeformation, hive, presto, trino, amazon-emr-emrfs."));
            assessedServices.add(AssessedService.skipped(
                    batch.getServiceName(), batch.getServiceType(), batch.getSkipReason()));
        } else {
            allPolicies.addAll(batch.getPolicies());
            assessedServices.add(AssessedService.assessed(
                    batch.getServiceName(), batch.getServiceType(), batch.getPolicies().size()));
        }
    }

    // ... rest of conversion pipeline unchanged (RangerToCedarConverter, CedarToLFConverter, S3AG) ...

    // Pass source label and services list into result
    return new AssessmentResult(
            allPolicies.size(), counts[0], counts[1], counts[2],
            ops.size(), gapReport,
            source.sourceLabel(), assessedServices);
}
```

Remove the now-dead `fetchPolicies(AssessmentConfig)` and `buildAdapterRegistry(AssessmentConfig)` methods entirely.

- [ ] **Step 4: Run tests**

```bash
mvn test -q
```

Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentRunner.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentRunnerTest.java
git commit -m "refactor: AssessmentRunner accepts PolicySource; remove fetchPolicies and buildAdapterRegistry"
```

---

## Task 8: `AssessmentReporter` — always render source/services preamble

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java`

- [ ] **Step 1: Add a failing test for the preamble**

Add to `AssessmentReporterTest`:

```java
@Test
void report_alwaysPrintsPreambleWithSourceAndServices() {
    AssessedService svc1 = AssessedService.assessed("hive_prod", "hive", 16);
    AssessedService svc2 = AssessedService.skipped("yarn_prod", "yarn", "unsupported service type");
    AssessmentResult result = buildResult(16, 16, 0, 0, 5, List.of(),
            "file:export.json", List.of(svc1, svc2));
    AssessmentConfig config = AssessmentConfig.builder().consoleOnly(true).build();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    new AssessmentReporter().report(result, config, new PrintStream(baos));
    String output = baos.toString();

    assertTrue(output.contains("Source:"), "Missing Source line");
    assertTrue(output.contains("file:export.json"), "Missing source label");
    assertTrue(output.contains("hive_prod"), "Missing assessed service");
    assertTrue(output.contains("yarn_prod"), "Missing skipped service");
    assertTrue(output.contains("skipped"), "Missing skipped status");
}
```

Update `buildResult` helper to accept the new signature:

```java
private AssessmentResult buildResult(int total, int fully, int partial, int notConv,
                                     int grants, List<GapEntry> entries,
                                     String source, List<AssessedService> services) {
    GapReport gapReport = new GapReport(entries, GapReport.computeSummary(entries), "2024-01-01T00:00:00Z");
    return new AssessmentResult(total, fully, partial, notConv, grants, gapReport, source, services);
}
```

Update existing `buildResult` calls in the test to pass `null, List.of()` for the two new args.

- [ ] **Step 2: Run to confirm new test fails**

```bash
mvn test -pl . -Dtest=AssessmentReporterTest#report_alwaysPrintsPreambleWithSourceAndServices -q
```

Expected: FAIL — preamble not present

- [ ] **Step 3: Update `AssessmentReporter.printConsoleReport()`**

Add at the start of `printConsoleReport()` after the header line:

```java
// Source line
if (result.getSource() != null) {
    out.println("Source:       " + result.getSource());
}
out.println("Assessed at:  " + result.getGapReport().getGeneratedAt());
out.println();

// Services table
if (!result.getServices().isEmpty()) {
    out.println("Services assessed:");
    for (AssessedService svc : result.getServices()) {
        if (svc.isSkipped() /* add isSkipped() helper */ ) {
            out.printf("  %-20s (%-16s) — skipped: %s%n",
                    svc.getName(), svc.getServiceType(), svc.getSkipReason());
        } else {
            out.printf("  %-20s (%-16s) — assessed  (%d policies)%n",
                    svc.getName(), svc.getServiceType(), svc.getPoliciesScanned());
        }
    }
    out.println();
}
```

Add `isSkipped()` to `AssessedService`:

```java
public boolean isSkipped() { return "skipped".equals(status); }
```

Remove the standalone `out.println("Assessed at: ...")` that was previously the first line after the header (it is now emitted inside the new block above).

- [ ] **Step 4: Run all reporter tests**

```bash
mvn test -pl . -Dtest=AssessmentReporterTest -q
```

Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/assessment/AssessmentReporter.java \
        src/main/java/com/amazonaws/policyconverters/assessment/AssessedService.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentReporterTest.java
git commit -m "feat: AssessmentReporter always renders source/services preamble"
```

---

## Task 9: `AssessmentMain` — subcommand dispatch

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/app/AssessmentMain.java`
- Create: `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentMainTest.java`

- [ ] **Step 1: Write failing dispatch tests**

Create `src/test/java/com/amazonaws/policyconverters/assessment/AssessmentMainTest.java`:

```java
package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.app.AssessmentMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessmentMainTest {

    @Test
    void run_noArgs_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{}));
    }

    @Test
    void run_unknownSubcommand_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{"--ranger-url", "http://localhost"}));
    }

    @Test
    void run_serverSubcommand_missingRangerUrl_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{"server", "--console-only"}));
    }

    @Test
    void run_fileSubcommand_nonExistentFile_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{"file", "/nonexistent/path.json"}));
    }

    @Test
    void run_fileSubcommand_withServicesFlag_exits1WithSpecificMessage(@TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("export.json");
        Files.writeString(file, "{\"policies\":[]}", StandardCharsets.UTF_8);

        // Capture stderr
        java.io.ByteArrayOutputStream errBytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream origErr = System.err;
        System.setErr(new java.io.PrintStream(errBytes));
        try {
            int code = AssessmentMain.run(new String[]{"file", file.toString(), "--services", "hive_prod"});
            assertEquals(1, code);
            String err = errBytes.toString();
            assertTrue(err.contains("--services is not supported in file mode"),
                    "Expected specific --services error message, got: " + err);
        } finally {
            System.setErr(origErr);
        }
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
mvn test -pl . -Dtest=AssessmentMainTest -q
```

Expected: compile or assertion failures (old flat dispatch)

- [ ] **Step 3: Rewrite `AssessmentMain.run()`**

Replace the entire `run()` method and add `parseServerArgs()` and `parseFileArgs()` helpers. Keep `applyConfigFile()` and `guessServiceType()` unchanged. New `USAGE` constant:

```java
private static final String USAGE = String.join(System.lineSeparator(),
        "Usage:",
        "  assess server [<config-file>] [options]",
        "    --ranger-url <url>        Ranger Admin URL (required if no config file)",
        "    --ranger-user <user>      Ranger Admin username",
        "    --ranger-password <pass>  Ranger Admin password",
        "    --services <s1,s2,...>    Comma-separated service instance names",
        "    --output-dir <dir>        Directory for JSON report (default: current dir)",
        "    --aws-region <region>     Enable Glue wildcard expansion",
        "    --console-only            Print report to console, skip JSON file",
        "",
        "  assess file <export-file.json> [options]",
        "    --output-dir <dir>        Directory for JSON report (default: current dir)",
        "    --aws-region <region>     Enable Glue wildcard expansion",
        "    --console-only            Print report to console, skip JSON file"
);

static int run(String[] args) {
    if (args.length == 0) {
        System.err.println(USAGE);
        return 1;
    }
    switch (args[0]) {
        case "server": return runServer(Arrays.copyOfRange(args, 1, args.length));
        case "file":   return runFile(Arrays.copyOfRange(args, 1, args.length));
        default:
            System.err.println("Unknown subcommand: " + args[0]);
            System.err.println(USAGE);
            return 1;
    }
}
```

`runServer()` — parses the same flags as the old `run()`, validates `rangerAdminUrl` is set, constructs `RangerAdminPolicySource`, calls `new AssessmentRunner().run(config, source)`.

`runFile()` — expects first positional arg as the file path (error if missing or unreadable), parses `--output-dir`, `--aws-region`, `--console-only`; rejects `--services` with specific message; constructs `RangerExportFilePolicySource`, calls `new AssessmentRunner().run(config, source)`.

- [ ] **Step 4: Run all tests**

```bash
mvn test -q
```

Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/app/AssessmentMain.java \
        src/test/java/com/amazonaws/policyconverters/assessment/AssessmentMainTest.java
git commit -m "feat: add assess server/file subcommand dispatch to AssessmentMain"
```

---

## Task 10: Update `README.md`

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the Pre-Migration Assessment Tool section**

Locate the section starting at "## Pre-Migration Assessment Tool" (around line 548). Make the following changes:

1. **Migration callout** — add immediately after the section heading:

```markdown
> **Breaking change:** The `assess` CLI now requires a subcommand. Update any scripts using the old syntax:
>
> ```bash
> # Before
> java -jar assessment-jar-with-dependencies.jar --ranger-url http://... --console-only
>
> # After
> java -jar assessment-jar-with-dependencies.jar server --ranger-url http://... --console-only
> ```
```

2. **Usage block** — replace the existing `assess [<config-file>] [options]` block with:

```
assess server [<config-file>] [options]
  --ranger-url <url>        Ranger Admin URL (required if no config file)
  --ranger-user <user>      Ranger Admin username
  --ranger-password <pass>  Ranger Admin password
  --services <s1,s2,...>    Comma-separated service instance names to assess
  --output-dir <dir>        Directory for JSON report (default: current dir)
  --aws-region <region>     Enable Glue wildcard expansion with this region
  --console-only            Print report to console only, skip JSON file

assess file <export-file.json> [options]
  --output-dir <dir>        Directory for JSON report (default: current dir)
  --aws-region <region>     Enable Glue wildcard expansion with this region
  --console-only            Print report to console only, skip JSON file
```

3. **Update all examples** — change each `java -jar ... assess [flags]` example to `java -jar ... server [flags]` and add a new `assess file` example:

```bash
# Assess from a Ranger export file (no Ranger Admin or AWS credentials needed):
java -jar target/assessment-jar-with-dependencies.jar \
  file ./ranger-export.json \
  --console-only
```

4. **Add "Obtaining a Ranger Export File" subsection** before the examples:

```markdown
### Obtaining a Ranger Export File

In the Ranger Admin UI, navigate to **Security Zone → Export** or the service policy list and use the **Export** button. Select **Export Type: JSON** and save the file. If your browser downloads a ZIP archive, unzip it first — only the `.json` file is supported.
```

5. **Update the JSON Report Format example** — add `"source"` and `"services"` fields to the top of the example object:

```json
{
  "source": "ranger-admin:http://ranger-admin:6080",
  "services": [
    { "name": "lf_prod", "serviceType": "lakeformation", "status": "assessed", "policiesScanned": 31 }
  ],
  "totalPolicies": 47,
  ...
}
```

6. **Update the `UNSUPPORTED_SERVICE_TYPE` row** in the Gap Types table to note it appears at the service level in file mode:

```
| `UNSUPPORTED_SERVICE_TYPE` | No adapter registered for this Ranger service type. In `assess server` mode, the entire policy is skipped. In `assess file` mode, one entry is recorded per skipped service (not per policy), and the `details` field includes the count of bypassed policies. |
```

- [ ] **Step 2: Verify build still passes**

```bash
mvn test -q
```

Expected: all pass (README change has no test impact)

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: update README for assess server/file subcommands and export file mode"
```

---

## Task 11: Full test suite and smoke check

- [ ] **Step 1: Run the complete test suite**

```bash
mvn test
```

Expected: BUILD SUCCESS, zero failures

- [ ] **Step 2: Build the fat JAR**

```bash
mvn package -DskipTests -q
```

Expected: BUILD SUCCESS, `target/assessment-jar-with-dependencies.jar` present

- [ ] **Step 3: Smoke-test the `server` subcommand error path (no network needed)**

```bash
java -jar target/assessment-jar-with-dependencies.jar server --console-only
```

Expected: exit 1 with a message about missing `--ranger-url`

- [ ] **Step 4: Smoke-test the `file` subcommand with a minimal export file**

```bash
echo '{"policies":[]}' > /tmp/test-export.json
java -jar target/assessment-jar-with-dependencies.jar file /tmp/test-export.json --console-only
```

Expected: runs without error, prints assessment header with zero policies

- [ ] **Step 5: Smoke-test unknown subcommand**

```bash
java -jar target/assessment-jar-with-dependencies.jar --ranger-url http://localhost
```

Expected: exit 1, USAGE printed

- [ ] **Step 6: Final commit (if any fixups needed)**

```bash
git add -p  # stage any fixups
git commit -m "fix: address smoke-test issues"
```
