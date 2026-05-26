# Track 2: Real-World Simulator — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a configurable-duration simulator that continuously mutates Ranger policies against a real AWS account, validates that Lake Formation and S3 Access Grants permissions are always correct, and on any violation writes a full reproduction bundle and attempts self-healing.

**Architecture:** New Maven module `simulator/` in the repo root. The sync service gains a `GET /status` HTTP endpoint (embedded Sun HttpServer — no new dependencies) exposing two monotonic cycle counters. The simulator runs as a separate process alongside the sync service in a dedicated AWS account with pre-provisioned IAM roles and a Glue catalog.

**Tech Stack:** Java 17, AWS SDK v2 (lakeformation + s3control already in BOM), Jackson 2.15.3 (already in BOM), JUnit 5 + jqwik for simulator unit tests, Maven Failsafe for ITs. No new external dependencies.

**Prerequisites before starting Track 2:**
- Track 1 complete
- Dedicated AWS account created
- IAM roles (`analyst`, `etl_user`, `data_admin`, `viewer`) pre-created with ARNs noted
- Glue catalog pre-populated: 3 databases (`finance`, `marketing`, `ops`), ~10 tables each
- S3 Access Grants instance created in the account
- Ranger Admin running (same Docker setup as integration tests)

---

## File Map

### Production — Sync Service Changes

| File | Create / Modify |
|------|-----------------|
| `src/main/java/com/amazonaws/policyconverters/app/StatusEndpoint.java` | Create |
| `src/main/java/com/amazonaws/policyconverters/app/WildcardRefreshScheduler.java` | Modify — add `lastCompletedWildcardRefreshCycle` counter |
| `src/main/java/com/amazonaws/policyconverters/sync/SyncService.java` | Modify — add `lastCompletedCycle` counter |
| `src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java` | Modify — start StatusEndpoint |

### Production — Simulator Module

| File | Create |
|------|--------|
| `simulator/pom.xml` | New Maven module POM |
| `simulator/src/main/java/.../simulator/workload/MutationOperation.java` | Sealed mutation type |
| `simulator/src/main/java/.../simulator/workload/MutationLog.java` | On-disk append log |
| `simulator/src/main/java/.../simulator/workload/HivePolicyGenerator.java` | Hive/Trino policy generator |
| `simulator/src/main/java/.../simulator/workload/DataLocationPolicyGenerator.java` | LF DATA_LOCATION_ACCESS generator |
| `simulator/src/main/java/.../simulator/workload/EmrfsPolicyGenerator.java` | S3 Access Grants / EMRFS generator |
| `simulator/src/main/java/.../simulator/workload/TagPolicyGenerator.java` | Tag-based policy generator (always gaps) |
| `simulator/src/main/java/.../simulator/workload/WorkloadOrchestrator.java` | Weighted random mutation selector |
| `simulator/src/main/java/.../simulator/driver/SimulatorConfig.java` | Config (runDurationMs, Ranger URL, etc.) |
| `simulator/src/main/java/.../simulator/driver/RangerPolicyClient.java` | Ranger REST API client |
| `simulator/src/main/java/.../simulator/driver/MutationDriver.java` | Main mutation loop |
| `simulator/src/main/java/.../simulator/driver/SimulatorMain.java` | Entry point + startup assertions |
| `simulator/src/main/java/.../simulator/driver/SimulatorCleanup.java` | Cleanup entry point |
| `simulator/src/main/java/.../simulator/status/SyncServiceStatusClient.java` | GET /status client |
| `simulator/src/main/java/.../simulator/status/CycleWaiter.java` | Polls until cycle > N |
| `simulator/src/main/java/.../simulator/validator/SimulatorPermission.java` | Internal value type |
| `simulator/src/main/java/.../simulator/validator/LFPermissionsFetcher.java` | Independent LF ListPermissions |
| `simulator/src/main/java/.../simulator/validator/S3AgPermissionsFetcher.java` | Independent S3AG ListAccessGrants |
| `simulator/src/main/java/.../simulator/validator/ExpectedPermissionsComputer.java` | Independent expected-state computer |
| `simulator/src/main/java/.../simulator/validator/Phase1DriftValidator.java` | Checkpoint vs. actual diff |
| `simulator/src/main/java/.../simulator/validator/Phase2CorrectnessValidator.java` | Independent correctness check |
| `simulator/src/main/java/.../simulator/validator/ValidationResult.java` | PASS/TRANSIENT/PERSISTENT |
| `simulator/src/main/java/.../simulator/remediation/ReproductionBundle.java` | Bundle data record |
| `simulator/src/main/java/.../simulator/remediation/BundleWriter.java` | Writes bundle to disk |
| `simulator/src/main/java/.../simulator/remediation/RemediationRunner.java` | Trigger + re-validate |
| `simulator/src/main/java/.../simulator/alert/AlertEmitter.java` | Interface |
| `simulator/src/main/java/.../simulator/alert/LogFileAlertEmitter.java` | Log-file implementation |

### Tests

| File | Create |
|------|--------|
| `simulator/src/test/java/.../simulator/WorkloadOrchestratorTest.java` | Unit test |
| `simulator/src/test/java/.../simulator/ExpectedPermissionsComputerTest.java` | Unit test — independence verified |

---

## Task 1: Maven Module Skeleton

**Files:**
- Create: `simulator/pom.xml`
- Modify: `pom.xml` (root)

- [ ] **Step 1: Add the `simulator` module to the root POM**

Open `pom.xml` (root) and add `<module>simulator</module>` to the `<modules>` section.

- [ ] **Step 2: Create `simulator/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example.ranger.lakeformation</groupId>
        <artifactId>ranger-lakeformation-plugin</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>ranger-lakeformation-simulator</artifactId>
    <name>Ranger LakeFormation Simulator</name>

    <dependencies>
        <!-- AWS SDK v2 — versions from parent BOM -->
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>lakeformation</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>s3control</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>glue</artifactId>
        </dependency>
        <!-- Jackson for Ranger REST client and JSON output -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <!-- HTTP client for Ranger Admin REST calls and /status polling -->
        <dependency>
            <groupId>org.apache.httpcomponents.client5</groupId>
            <artifactId>httpclient5</artifactId>
            <version>5.3.1</version>
        </dependency>
        <!-- SLF4J — same as parent -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>
        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>net.jqwik</groupId>
            <artifactId>jqwik</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <includes>
                        <include>**/*Test.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create the package directory structure**

```bash
mkdir -p simulator/src/main/java/com/amazonaws/policyconverters/simulator/{workload,driver,status,validator,remediation,alert}
mkdir -p simulator/src/test/java/com/amazonaws/policyconverters/simulator
```

- [ ] **Step 4: Verify the module compiles (empty)**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` (no sources yet — that's fine).

- [ ] **Step 5: Commit**

```bash
git add pom.xml simulator/pom.xml simulator/src/
git commit -m "build: add simulator Maven module skeleton"
```

---

## Task 2: Sync Service Status Endpoint

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/app/StatusEndpoint.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/sync/SyncService.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/app/WildcardRefreshScheduler.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java`

Uses `com.sun.net.httpserver.HttpServer` — available in the JDK, no new dependency.

- [ ] **Step 1: Write the failing test for StatusEndpoint**

Create `src/test/java/com/amazonaws/policyconverters/app/StatusEndpointTest.java`:

```java
package com.amazonaws.policyconverters.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StatusEndpointTest {

    private StatusEndpoint endpoint;
    private AtomicLong cycleCounter;
    private AtomicLong wildcardRefreshCounter;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        cycleCounter = new AtomicLong(0);
        wildcardRefreshCounter = new AtomicLong(0);
        port = 18080;  // test port
        endpoint = new StatusEndpoint(port, cycleCounter, wildcardRefreshCounter);
        endpoint.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        endpoint.stop();
    }

    @Test
    void getStatusReturnsJsonWithCounters() throws Exception {
        cycleCounter.set(5);
        wildcardRefreshCounter.set(3);

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"lastCompletedCycle\":5"));
        assertTrue(response.body().contains("\"lastCompletedWildcardRefreshCycle\":3"));
        assertTrue(response.body().contains("\"state\":\"running\""));
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails (class doesn't exist yet)**

```bash
mvn test -pl . -Dtest=StatusEndpointTest -q 2>&1 | tail -10
```

Expected: FAIL with compilation error.

- [ ] **Step 3: Create `StatusEndpoint.java`**

```java
package com.amazonaws.policyconverters.app;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public class StatusEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(StatusEndpoint.class);

    private final int port;
    private final AtomicLong lastCompletedCycle;
    private final AtomicLong lastCompletedWildcardRefreshCycle;
    private HttpServer server;

    public StatusEndpoint(int port,
                          AtomicLong lastCompletedCycle,
                          AtomicLong lastCompletedWildcardRefreshCycle) {
        this.port = port;
        this.lastCompletedCycle = lastCompletedCycle;
        this.lastCompletedWildcardRefreshCycle = lastCompletedWildcardRefreshCycle;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/status", exchange -> {
            String body = String.format(
                    "{\"lastCompletedCycle\":%d,\"lastCompletedWildcardRefreshCycle\":%d,\"state\":\"running\"}",
                    lastCompletedCycle.get(),
                    lastCompletedWildcardRefreshCycle.get());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        server.start();
        LOG.info("StatusEndpoint started on port {}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
```

- [ ] **Step 4: Add `lastCompletedCycle` counter to `SyncService`**

Open `SyncService.java`. Add this field near the top (after existing `volatile` fields):

```java
private final AtomicLong lastCompletedCycle = new AtomicLong(0);
```

Add this import at the top:
```java
import java.util.concurrent.atomic.AtomicLong;
```

Add a public getter:
```java
public AtomicLong getLastCompletedCycle() {
    return lastCompletedCycle;
}
```

Find `executeSyncCycle()` and add `lastCompletedCycle.incrementAndGet();` as the **last statement** inside the method body (after all LF and S3AG operations complete). This ensures the counter only increments on a fully completed cycle.

- [ ] **Step 5: Add `lastCompletedWildcardRefreshCycle` counter to `WildcardRefreshScheduler`**

Open `WildcardRefreshScheduler.java`. Add field:

```java
private final AtomicLong lastCompletedWildcardRefreshCycle = new AtomicLong(0);
```

In `executeRefreshCycle()`, add `lastCompletedWildcardRefreshCycle.incrementAndGet();` in the inner `finally` block (right after `cycleLock.unlock()`):

```java
private void executeRefreshCycle() {
    try {
        cycleLock.lock();
        try {
            WildcardRefreshResult result = syncService.executeWildcardRefresh();
            metricsEmitter.recordWildcardRefresh(result);
        } finally {
            cycleLock.unlock();
            lastCompletedWildcardRefreshCycle.incrementAndGet();  // ← add here
        }
    } catch (Exception e) {
        LOG.error("Wildcard refresh cycle failed unexpectedly: {}", e.getMessage(), e);
    }
}
```

Add a public getter:
```java
public AtomicLong getLastCompletedWildcardRefreshCycle() {
    return lastCompletedWildcardRefreshCycle;
}
```

- [ ] **Step 6: Wire StatusEndpoint into `ConversionServerMain`**

Open `ConversionServerMain.java`. Find where `syncService` and `wildcardRefreshScheduler` are constructed and add:

```java
// After constructing syncService and wildcardRefreshScheduler:
int statusPort = serverConfig.getStatusEndpointPort();  // add to ServerConfig or default to 18080
StatusEndpoint statusEndpoint = new StatusEndpoint(
        statusPort,
        syncService.getLastCompletedCycle(),
        wildcardRefreshScheduler.getLastCompletedWildcardRefreshCycle());
statusEndpoint.start();
// Add statusEndpoint.stop() to the shutdown hook
```

If `ServerConfig` doesn't have a status port field, default to `18080` directly. Add the shutdown hook registration next to the existing ones.

- [ ] **Step 7: Run the StatusEndpoint test**

```bash
mvn test -pl . -Dtest=StatusEndpointTest -q 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 8: Compile the whole project**

```bash
mvn compile -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Run all unit tests**

```bash
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` — no regressions.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/app/StatusEndpoint.java \
        src/main/java/com/amazonaws/policyconverters/sync/SyncService.java \
        src/main/java/com/amazonaws/policyconverters/app/WildcardRefreshScheduler.java \
        src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java \
        src/test/java/com/amazonaws/policyconverters/app/StatusEndpointTest.java
git commit -m "feat: add GET /status endpoint with lastCompletedCycle and lastCompletedWildcardRefreshCycle"
```

---

## Task 3: SimulatorPermission and Core Value Types

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/SimulatorPermission.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/ValidationResult.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/MutationOperation.java`

- [ ] **Step 1: Create `SimulatorPermission.java`**

```java
package com.amazonaws.policyconverters.simulator.validator;

import java.util.Objects;
import java.util.Set;

/**
 * Internal value type representing one LF or S3AG permission for comparison.
 * Used by both Phase1DriftValidator and Phase2CorrectnessValidator.
 * Does NOT import any class from the main conversion pipeline.
 */
public final class SimulatorPermission {
    public enum Backend { LAKE_FORMATION, S3_ACCESS_GRANTS }

    private final Backend backend;
    private final String principalArn;
    // LF fields (null for S3AG)
    private final String catalogId;
    private final String database;
    private final String table;
    private final Set<String> columnNames;   // empty = table-level (not wildcard)
    private final boolean columnWildcard;    // true = TableWithColumnsResource + ColumnWildcard
    private final String rowFilterExpression; // null if none
    private final String dataLocationPath;   // non-null for DATA_LOCATION_ACCESS
    private final Set<String> permissions;   // e.g. {"SELECT", "INSERT"}
    private final boolean grantable;
    // S3AG fields (null for LF)
    private final String s3Prefix;           // e.g. "s3://bucket/prefix"
    private final String s3Permission;       // READ, WRITE, or READWRITE

    // Full constructor — use static factory methods below
    private SimulatorPermission(Backend backend, String principalArn,
                                 String catalogId, String database, String table,
                                 Set<String> columnNames, boolean columnWildcard,
                                 String rowFilterExpression, String dataLocationPath,
                                 Set<String> permissions, boolean grantable,
                                 String s3Prefix, String s3Permission) {
        this.backend = backend;
        this.principalArn = principalArn;
        this.catalogId = catalogId;
        this.database = database;
        this.table = table;
        this.columnNames = columnNames == null ? Set.of() : Set.copyOf(columnNames);
        this.columnWildcard = columnWildcard;
        this.rowFilterExpression = rowFilterExpression;
        this.dataLocationPath = dataLocationPath;
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        this.grantable = grantable;
        this.s3Prefix = s3Prefix;
        this.s3Permission = s3Permission;
    }

    public static SimulatorPermission lfTablePermission(
            String principalArn, String catalogId, String database, String table,
            Set<String> permissions, boolean grantable) {
        return new SimulatorPermission(Backend.LAKE_FORMATION, principalArn,
                catalogId, database, table, Set.of(), false, null, null,
                permissions, grantable, null, null);
    }

    public static SimulatorPermission lfColumnPermission(
            String principalArn, String catalogId, String database, String table,
            Set<String> columnNames, Set<String> permissions, boolean grantable) {
        return new SimulatorPermission(Backend.LAKE_FORMATION, principalArn,
                catalogId, database, table, columnNames, false, null, null,
                permissions, grantable, null, null);
    }

    public static SimulatorPermission lfColumnWildcardPermission(
            String principalArn, String catalogId, String database, String table,
            Set<String> permissions, boolean grantable) {
        return new SimulatorPermission(Backend.LAKE_FORMATION, principalArn,
                catalogId, database, table, Set.of(), true, null, null,
                permissions, grantable, null, null);
    }

    public static SimulatorPermission lfRowFilterPermission(
            String principalArn, String catalogId, String database, String table,
            String rowFilterExpression, Set<String> permissions, boolean grantable) {
        return new SimulatorPermission(Backend.LAKE_FORMATION, principalArn,
                catalogId, database, table, Set.of(), false, rowFilterExpression, null,
                permissions, grantable, null, null);
    }

    public static SimulatorPermission lfDataLocationPermission(
            String principalArn, String dataLocationPath, boolean grantable) {
        return new SimulatorPermission(Backend.LAKE_FORMATION, principalArn,
                null, null, null, Set.of(), false, null, dataLocationPath,
                Set.of("DATA_LOCATION_ACCESS"), grantable, null, null);
    }

    public static SimulatorPermission s3AgPermission(
            String principalArn, String s3Prefix, String s3Permission) {
        return new SimulatorPermission(Backend.S3_ACCESS_GRANTS, principalArn,
                null, null, null, Set.of(), false, null, null,
                Set.of(), false, s3Prefix, s3Permission);
    }

    // Getters
    public Backend getBackend() { return backend; }
    public String getPrincipalArn() { return principalArn; }
    public String getCatalogId() { return catalogId; }
    public String getDatabase() { return database; }
    public String getTable() { return table; }
    public Set<String> getColumnNames() { return columnNames; }
    public boolean isColumnWildcard() { return columnWildcard; }
    public String getRowFilterExpression() { return rowFilterExpression; }
    public String getDataLocationPath() { return dataLocationPath; }
    public Set<String> getPermissions() { return permissions; }
    public boolean isGrantable() { return grantable; }
    public String getS3Prefix() { return s3Prefix; }
    public String getS3Permission() { return s3Permission; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SimulatorPermission)) return false;
        SimulatorPermission that = (SimulatorPermission) o;
        return columnWildcard == that.columnWildcard
                && grantable == that.grantable
                && backend == that.backend
                && Objects.equals(principalArn, that.principalArn)
                && Objects.equals(catalogId, that.catalogId)
                && Objects.equals(database, that.database)
                && Objects.equals(table, that.table)
                && Objects.equals(columnNames, that.columnNames)
                && Objects.equals(rowFilterExpression, that.rowFilterExpression)
                && Objects.equals(dataLocationPath, that.dataLocationPath)
                && Objects.equals(permissions, that.permissions)
                && Objects.equals(s3Prefix, that.s3Prefix)
                && Objects.equals(s3Permission, that.s3Permission);
    }

    @Override
    public int hashCode() {
        return Objects.hash(backend, principalArn, catalogId, database, table,
                columnNames, columnWildcard, rowFilterExpression, dataLocationPath,
                permissions, grantable, s3Prefix, s3Permission);
    }

    @Override
    public String toString() {
        if (backend == Backend.S3_ACCESS_GRANTS) {
            return String.format("S3AG[principal=%s, prefix=%s, permission=%s]",
                    principalArn, s3Prefix, s3Permission);
        }
        return String.format("LF[principal=%s, db=%s, table=%s, cols=%s, colWildcard=%b, " +
                "rowFilter=%s, dataLocation=%s, permissions=%s, grantable=%b]",
                principalArn, database, table, columnNames, columnWildcard,
                rowFilterExpression, dataLocationPath, permissions, grantable);
    }
}
```

- [ ] **Step 2: Create `ValidationResult.java`**

```java
package com.amazonaws.policyconverters.simulator.validator;

import java.util.Set;

public record ValidationResult(
        Status status,
        Set<SimulatorPermission> overGrants,    // in actual, not in expected
        Set<SimulatorPermission> underGrants,   // in expected, not in actual
        String details
) {
    public enum Status { PASS, VIOLATION }

    public boolean isViolation() { return status == Status.VIOLATION; }

    public static ValidationResult pass() {
        return new ValidationResult(Status.PASS, Set.of(), Set.of(), "");
    }

    public static ValidationResult violation(Set<SimulatorPermission> overGrants,
                                              Set<SimulatorPermission> underGrants) {
        return new ValidationResult(Status.VIOLATION, overGrants, underGrants,
                String.format("over-grants=%d, under-grants=%d",
                        overGrants.size(), underGrants.size()));
    }
}
```

- [ ] **Step 3: Create `MutationOperation.java`**

```java
package com.amazonaws.policyconverters.simulator.workload;

public enum MutationOperation {
    CREATE,   // create a new Ranger policy
    UPDATE,   // add or remove a principal from an existing policy
    DISABLE,  // disable an existing policy
    ENABLE,   // re-enable a disabled policy
    DELETE    // delete a policy entirely
}
```

- [ ] **Step 4: Compile the simulator module**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/
git commit -m "feat(simulator): add SimulatorPermission, ValidationResult, MutationOperation value types"
```

---

## Task 4: MutationLog and WorkloadOrchestrator

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/MutationLog.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/WorkloadOrchestrator.java`
- Create: `simulator/src/test/java/com/amazonaws/policyconverters/simulator/WorkloadOrchestratorTest.java`

- [ ] **Step 1: Write `WorkloadOrchestratorTest.java` (failing)**

```java
package com.amazonaws.policyconverters.simulator;

import com.amazonaws.policyconverters.simulator.workload.MutationOperation;
import com.amazonaws.policyconverters.simulator.workload.WorkloadOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadOrchestratorTest {

    @Test
    void selectsOperationWithinConfiguredWeights() {
        // Weights: CREATE=100, all others=0 → must always select CREATE
        Map<MutationOperation, Integer> weights = new EnumMap<>(MutationOperation.class);
        weights.put(MutationOperation.CREATE, 100);
        weights.put(MutationOperation.UPDATE, 0);
        weights.put(MutationOperation.DISABLE, 0);
        weights.put(MutationOperation.ENABLE, 0);
        weights.put(MutationOperation.DELETE, 0);
        WorkloadOrchestrator orchestrator = new WorkloadOrchestrator(weights, 3);

        for (int i = 0; i < 50; i++) {
            List<MutationOperation> batch = orchestrator.nextBatch();
            assertFalse(batch.isEmpty());
            assertTrue(batch.stream().allMatch(op -> op == MutationOperation.CREATE),
                    "With weight 100 on CREATE, all operations must be CREATE");
        }
    }

    @Test
    void batchSizeWithinConfiguredBounds() {
        WorkloadOrchestrator orchestrator = WorkloadOrchestrator.defaultWeights(5);
        for (int i = 0; i < 100; i++) {
            List<MutationOperation> batch = orchestrator.nextBatch();
            assertFalse(batch.isEmpty(), "Batch must contain at least 1 operation");
            assertTrue(batch.size() <= 5, "Batch must not exceed maxBatchSize=5");
        }
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
mvn test -pl simulator -Dtest=WorkloadOrchestratorTest -q 2>&1 | tail -10
```

Expected: FAIL (class doesn't exist).

- [ ] **Step 3: Create `MutationLog.java`**

```java
package com.amazonaws.policyconverters.simulator.workload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Append-only log of every mutation applied during a simulator run.
 * Written as newline-delimited JSON. Survives crashes — never truncated.
 */
public class MutationLog {
    private static final Logger LOG = LoggerFactory.getLogger(MutationLog.class);
    private final Path logFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public MutationLog(Path logFile) throws IOException {
        this.logFile = logFile;
        Files.createDirectories(logFile.getParent());
    }

    public void append(long cycleNumber, List<MutationOperation> operations,
                       List<Map<String, Object>> policyPayloads) throws IOException {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("timestamp", Instant.now().toString());
        entry.put("cycleNumber", cycleNumber);
        entry.set("operations", mapper.valueToTree(operations));
        entry.set("policyPayloads", mapper.valueToTree(policyPayloads));

        String line = mapper.writeValueAsString(entry) + "\n";
        Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Path getLogFile() { return logFile; }
}
```

- [ ] **Step 4: Create `WorkloadOrchestrator.java`**

```java
package com.amazonaws.policyconverters.simulator.workload;

import java.util.*;

public class WorkloadOrchestrator {
    private final Map<MutationOperation, Integer> weights;
    private final int maxBatchSize;
    private final Random random = new Random();
    private final List<MutationOperation> weightedPool;

    public WorkloadOrchestrator(Map<MutationOperation, Integer> weights, int maxBatchSize) {
        this.weights = Map.copyOf(weights);
        this.maxBatchSize = maxBatchSize;
        this.weightedPool = buildPool(weights);
        if (weightedPool.isEmpty()) {
            throw new IllegalArgumentException("At least one operation must have weight > 0");
        }
    }

    public static WorkloadOrchestrator defaultWeights(int maxBatchSize) {
        Map<MutationOperation, Integer> defaults = new EnumMap<>(MutationOperation.class);
        defaults.put(MutationOperation.CREATE,  30);
        defaults.put(MutationOperation.UPDATE,  25);
        defaults.put(MutationOperation.DISABLE, 15);
        defaults.put(MutationOperation.ENABLE,  20);
        defaults.put(MutationOperation.DELETE,  10);
        return new WorkloadOrchestrator(defaults, maxBatchSize);
    }

    public List<MutationOperation> nextBatch() {
        int size = 1 + random.nextInt(maxBatchSize);
        List<MutationOperation> batch = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            batch.add(weightedPool.get(random.nextInt(weightedPool.size())));
        }
        return Collections.unmodifiableList(batch);
    }

    private static List<MutationOperation> buildPool(Map<MutationOperation, Integer> weights) {
        List<MutationOperation> pool = new ArrayList<>();
        weights.forEach((op, weight) -> {
            for (int i = 0; i < weight; i++) pool.add(op);
        });
        return pool;
    }
}
```

- [ ] **Step 5: Run tests**

```bash
mvn test -pl simulator -Dtest=WorkloadOrchestratorTest -q 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/ \
        simulator/src/test/java/com/amazonaws/policyconverters/simulator/WorkloadOrchestratorTest.java
git commit -m "feat(simulator): add MutationLog and WorkloadOrchestrator"
```

---

## Task 5: SimulatorConfig and RangerPolicyClient

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/SimulatorConfig.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/RangerPolicyClient.java`

- [ ] **Step 1: Create `SimulatorConfig.java`**

```java
package com.amazonaws.policyconverters.simulator.driver;

import java.util.List;
import java.util.Map;

/**
 * All configuration for a simulator run. Load from environment variables or
 * command-line flags in SimulatorMain.
 */
public record SimulatorConfig(
        String rangerAdminUrl,          // e.g. http://localhost:6080
        String rangerAdminUser,         // e.g. admin
        String rangerAdminPassword,
        String rangerServiceName,       // e.g. lakeformation
        String awsRegion,               // e.g. us-east-1
        String awsAccountId,
        String s3AgInstanceArn,         // S3 Access Grants instance ARN
        Map<String, String> principalPool,  // name → IAM role ARN
        List<String> glueDatabases,     // pre-populated Glue databases
        long runDurationMs,             // 0 = run indefinitely
        int maxBatchSize,               // max operations per mutation cycle (default 5)
        long policyRefreshIntervalMs,   // sync service's refresh interval (for timeout calc)
        String syncServiceStatusUrl,    // e.g. http://localhost:18080/status
        String outputDirectory,         // where to write reproduction bundles
        String mutationLogPath,         // path for MutationLog JSON-L file
        String checkpointFilePath       // path to sync service's checkpoint JSON (for Phase 1)
) {
    public static final long INDEFINITE = 0L;

    public long cycleWaiterTimeoutMs() {
        return 3 * policyRefreshIntervalMs;
    }
}
```

- [ ] **Step 2: Create `RangerPolicyClient.java`**

This client wraps the Ranger Admin REST API. Use Apache HttpClient5 (in the simulator POM).

```java
package com.amazonaws.policyconverters.simulator.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Thin client over the Ranger Admin REST API v2.
 * Supports: create policy, get policies by service, update policy, delete policy.
 */
public class RangerPolicyClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RangerPolicyClient.class);

    private final String baseUrl;
    private final String authHeader;  // Basic auth, base64-encoded
    private final CloseableHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public RangerPolicyClient(String baseUrl, String user, String password) {
        this.baseUrl = baseUrl;
        String credentials = Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.authHeader = "Basic " + credentials;
        this.http = HttpClients.createDefault();
    }

    /** Create a policy from a JSON payload string. Returns the created policy's id. */
    public long createPolicy(String policyJson) throws IOException {
        HttpPost post = new HttpPost(baseUrl + "/service/public/v2/api/policy");
        post.setHeader("Authorization", authHeader);
        post.setHeader("Accept", "application/json");
        post.setEntity(new StringEntity(policyJson, ContentType.APPLICATION_JSON));

        return http.execute(post, response -> {
            int status = response.getCode();
            String body = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            if (status != 200) {
                throw new IOException("Create policy failed: HTTP " + status + " — " + body);
            }
            return mapper.readTree(body).get("id").asLong();
        });
    }

    /** Fetch all policies for a service. Returns raw JSON array as list of JsonNode. */
    public List<JsonNode> getPoliciesByService(String serviceName) throws IOException {
        HttpGet get = new HttpGet(baseUrl + "/service/public/v2/api/service/" + serviceName + "/policy");
        get.setHeader("Authorization", authHeader);
        get.setHeader("Accept", "application/json");

        return http.execute(get, response -> {
            int status = response.getCode();
            String body = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            if (status != 200) {
                throw new IOException("Get policies failed: HTTP " + status + " — " + body);
            }
            JsonNode node = mapper.readTree(body);
            List<JsonNode> policies = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(policies::add);
            }
            return policies;
        });
    }

    /** Update a policy by id. Merges the provided JSON patch into the existing policy. */
    public void updatePolicy(long policyId, String policyJson) throws IOException {
        HttpPut put = new HttpPut(baseUrl + "/service/public/v2/api/policy/" + policyId);
        put.setHeader("Authorization", authHeader);
        put.setHeader("Accept", "application/json");
        put.setEntity(new StringEntity(policyJson, ContentType.APPLICATION_JSON));

        http.execute(put, response -> {
            int status = response.getCode();
            if (status != 200) {
                String body = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Update policy failed: HTTP " + status + " — " + body);
            }
            return null;
        });
    }

    /** Delete a policy by id. Idempotent — 404 is treated as success. */
    public void deletePolicy(long policyId) throws IOException {
        HttpDelete delete = new HttpDelete(baseUrl + "/service/public/v2/api/policy/" + policyId);
        delete.setHeader("Authorization", authHeader);

        http.execute(delete, response -> {
            int status = response.getCode();
            if (status != 204 && status != 200 && status != 404) {
                String body = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Delete policy failed: HTTP " + status + " — " + body);
            }
            return null;
        });
    }

    @Override
    public void close() throws IOException {
        http.close();
    }
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/
git commit -m "feat(simulator): add SimulatorConfig and RangerPolicyClient"
```

---

## Task 6: CycleWaiter and SyncServiceStatusClient

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/status/SyncServiceStatusClient.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/status/CycleWaiter.java`

- [ ] **Step 1: Create `SyncServiceStatusClient.java`**

```java
package com.amazonaws.policyconverters.simulator.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SyncServiceStatusClient {
    private final String statusUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SyncServiceStatusClient(String statusUrl) {
        this.statusUrl = statusUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record StatusSnapshot(long lastCompletedCycle, long lastCompletedWildcardRefreshCycle) {}

    public StatusSnapshot getStatus() throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(statusUrl))
                        .GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET /status returned HTTP " + response.statusCode());
        }
        JsonNode node = mapper.readTree(response.body());
        return new StatusSnapshot(
                node.get("lastCompletedCycle").asLong(),
                node.get("lastCompletedWildcardRefreshCycle").asLong());
    }
}
```

- [ ] **Step 2: Create `CycleWaiter.java`**

```java
package com.amazonaws.policyconverters.simulator.status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Polls GET /status until the sync service has completed at least one cycle
 * after the given snapshot, AND the wildcard refresh cycle counter has not
 * decreased (ensuring no in-flight wildcard refresh at snapshot time is missed).
 */
public class CycleWaiter {
    private static final Logger LOG = LoggerFactory.getLogger(CycleWaiter.class);
    private static final long POLL_INTERVAL_MS = 2_000L;

    private final SyncServiceStatusClient statusClient;
    private final long timeoutMs;

    public CycleWaiter(SyncServiceStatusClient statusClient, long timeoutMs) {
        this.statusClient = statusClient;
        this.timeoutMs = timeoutMs;
    }

    public enum WaitResult { SETTLED, TIMEOUT }

    /**
     * Block until lastCompletedCycle > N and lastCompletedWildcardRefreshCycle >= W.
     *
     * @param snapshotN cycle counter value before mutations were applied
     * @param snapshotW wildcard refresh counter value before mutations were applied
     */
    public WaitResult waitUntilSettled(long snapshotN, long snapshotW)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            SyncServiceStatusClient.StatusSnapshot current = statusClient.getStatus();
            if (current.lastCompletedCycle() > snapshotN
                    && current.lastCompletedWildcardRefreshCycle() >= snapshotW) {
                LOG.debug("Cycle settled: cycle={}, wildcardRefresh={}",
                        current.lastCompletedCycle(), current.lastCompletedWildcardRefreshCycle());
                return WaitResult.SETTLED;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        LOG.warn("CYCLE_WAIT_TIMEOUT: cycle did not advance past {} within {}ms. " +
                 "Skipping validation for this iteration.", snapshotN, timeoutMs);
        return WaitResult.TIMEOUT;
    }
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/status/
git commit -m "feat(simulator): add SyncServiceStatusClient and CycleWaiter"
```

---

## Task 7: LFPermissionsFetcher and S3AgPermissionsFetcher

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/LFPermissionsFetcher.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/S3AgPermissionsFetcher.java`

These are **independent** implementations using AWS SDK v2 directly. They do NOT import any class from the main conversion pipeline.

- [ ] **Step 1: Create `LFPermissionsFetcher.java`**

```java
package com.amazonaws.policyconverters.simulator.validator;

import software.amazon.awssdk.services.lakeformation.LakeFormationClient;
import software.amazon.awssdk.services.lakeformation.model.*;

import java.util.*;

/**
 * Fetches all current LF permissions via paginated ListPermissions.
 * Does NOT import or use any class from the main conversion pipeline.
 */
public class LFPermissionsFetcher {
    private final LakeFormationClient lf;
    private final String catalogId;

    public LFPermissionsFetcher(LakeFormationClient lf, String catalogId) {
        this.lf = lf;
        this.catalogId = catalogId;
    }

    public Set<SimulatorPermission> fetchAll() {
        Set<SimulatorPermission> result = new HashSet<>();
        String nextToken = null;
        do {
            ListPermissionsRequest.Builder req = ListPermissionsRequest.builder()
                    .catalogId(catalogId);
            if (nextToken != null) req.nextToken(nextToken);

            ListPermissionsResponse response = lf.listPermissions(req.build());
            for (PrincipalResourcePermissions prp : response.principalResourcePermissions()) {
                result.addAll(toSimulatorPermissions(prp));
            }
            nextToken = response.nextToken();
        } while (nextToken != null);
        return result;
    }

    private List<SimulatorPermission> toSimulatorPermissions(PrincipalResourcePermissions prp) {
        String principalArn = prp.principal().dataLakePrincipalIdentifier();
        boolean grantable = !prp.permissionsWithGrantOption().isEmpty();
        Resource resource = prp.resource();
        Set<String> permissions = new HashSet<>();
        prp.permissions().forEach(p -> permissions.add(p.name()));

        List<SimulatorPermission> results = new ArrayList<>();

        if (resource.table() != null) {
            TableResource t = resource.table();
            String db = t.databaseName();
            String table = t.name();
            if (table != null) {
                // Check for row filter
                String rowFilter = null;
                if (prp.resource().table() != null) {
                    // Row filters appear on TableResource when a RowFilter is attached
                    // The LF SDK attaches RowFilter data on the permission entry, not the resource.
                    // The permissionsWithGrantOption / permissions do not carry it; it is on the
                    // top-level PrincipalResourcePermissions via additionalDetails (not in SDK v2).
                    // For table-level permissions with no column restriction, a RowFilter is
                    // represented as a separate permission entry — we check via rowFilter field:
                    rowFilter = null; // LF SDK v2 does not expose RowFilter on table resource entries
                }
                results.add(SimulatorPermission.lfTablePermission(
                        principalArn, catalogId, db, table, permissions, grantable));
            }
        } else if (resource.tableWithColumns() != null) {
            TableWithColumnsResource twc = resource.tableWithColumns();
            String db = twc.databaseName();
            String table = twc.name();
            // Row filter: LF SDK v2 exposes it via rowFilter() on TableWithColumnsResource
            String rowFilter = twc.rowFilter() != null ? twc.rowFilter().filterExpression() : null;
            if (twc.columnWildcard() != null) {
                if (rowFilter != null) {
                    results.add(SimulatorPermission.lfRowFilterPermission(
                            principalArn, catalogId, db, table, rowFilter, permissions, grantable));
                } else {
                    results.add(SimulatorPermission.lfColumnWildcardPermission(
                            principalArn, catalogId, db, table, permissions, grantable));
                }
            } else {
                Set<String> cols = new HashSet<>(twc.columnNames());
                results.add(SimulatorPermission.lfColumnPermission(
                        principalArn, catalogId, db, table, cols, permissions, grantable));
            }
        } else if (resource.dataLocation() != null) {
            String s3Path = resource.dataLocation().resourceArn();
            results.add(SimulatorPermission.lfDataLocationPermission(
                    principalArn, s3Path, grantable));
        }

        return results;
    }
}
```

- [ ] **Step 2: Create `S3AgPermissionsFetcher.java`**

```java
package com.amazonaws.policyconverters.simulator.validator;

import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.*;

import java.util.HashSet;
import java.util.Set;

public class S3AgPermissionsFetcher {
    private final S3ControlClient s3control;
    private final String accountId;
    private final String instanceArn;

    public S3AgPermissionsFetcher(S3ControlClient s3control,
                                   String accountId, String instanceArn) {
        this.s3control = s3control;
        this.accountId = accountId;
        this.instanceArn = instanceArn;
    }

    public Set<SimulatorPermission> fetchAll() {
        Set<SimulatorPermission> result = new HashSet<>();
        String nextToken = null;
        do {
            ListAccessGrantsRequest.Builder req = ListAccessGrantsRequest.builder()
                    .accountId(accountId)
                    .accessGrantsInstanceArn(instanceArn);
            if (nextToken != null) req.nextToken(nextToken);

            ListAccessGrantsResponse response = s3control.listAccessGrants(req.build());
            for (ListAccessGrantEntry entry : response.accessGrantsList()) {
                String principalArn = entry.grantee().iamRoleArn() != null
                        ? entry.grantee().iamRoleArn()
                        : entry.grantee().directoryUserId();
                String s3Prefix = entry.accessGrantsLocation().s3SubPrefix();
                String permission = entry.permission().name(); // READ, WRITE, READWRITE
                result.add(SimulatorPermission.s3AgPermission(principalArn, s3Prefix, permission));
            }
            nextToken = response.nextToken();
        } while (nextToken != null);
        return result;
    }
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/
git commit -m "feat(simulator): add independent LFPermissionsFetcher and S3AgPermissionsFetcher"
```

---

## Task 8: ExpectedPermissionsComputer and Unit Tests

This is the independence-critical class. It reimplements the Ranger→LF/S3AG mapping logic from scratch. **Zero imports from the main conversion pipeline.**

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/ExpectedPermissionsComputer.java`
- Create: `simulator/src/test/java/com/amazonaws/policyconverters/simulator/ExpectedPermissionsComputerTest.java`

- [ ] **Step 1: Write the failing test first**

```java
package com.amazonaws.policyconverters.simulator;

import com.amazonaws.policyconverters.simulator.validator.ExpectedPermissionsComputer;
import com.amazonaws.policyconverters.simulator.validator.SimulatorPermission;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies ExpectedPermissionsComputer independently computes correct permission sets
 * from raw Ranger policy JSON, without using any class from the main conversion pipeline.
 */
class ExpectedPermissionsComputerTest {

    private ExpectedPermissionsComputer computer;

    @BeforeEach
    void setUp() {
        computer = new ExpectedPermissionsComputer(
                "123456789012",          // catalogId
                "us-east-1",             // region
                Map.of("analyst",  "arn:aws:iam::123456789012:role/AnalystRole",
                       "etl_user", "arn:aws:iam::123456789012:role/EtlRole"),
                "arn:aws:s3::123456789012:accessgrantsinstance/default"
        );
    }

    @Test
    void selectPolicyProducesTablePermission() {
        // Simple allow: analyst SELECT on analytics.events
        String policyJson = """
                {
                  "id": 1,
                  "service": "lakeformation",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["analytics"] },
                    "table":    { "values": ["events"] }
                  },
                  "policyItems": [
                    { "users": ["analyst"], "accesses": [{"type": "select", "isAllowed": true}],
                      "delegateAdmin": false }
                  ]
                }
                """;

        Set<SimulatorPermission> perms = computer.compute(List.of(policyJson));

        assertEquals(1, perms.size());
        SimulatorPermission perm = perms.iterator().next();
        assertEquals("arn:aws:iam::123456789012:role/AnalystRole", perm.getPrincipalArn());
        assertEquals("analytics", perm.getDatabase());
        assertEquals("events", perm.getTable());
        assertTrue(perm.getPermissions().contains("SELECT"));
        assertFalse(perm.isGrantable());
    }

    @Test
    void denyPolicyProducesNoPermissions() {
        String policyJson = """
                {
                  "id": 2,
                  "service": "lakeformation",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["analytics"] },
                    "table":    { "values": ["events"] }
                  },
                  "policyItems": [],
                  "denyPolicyItems": [
                    { "users": ["analyst"], "accesses": [{"type": "select", "isAllowed": true}] }
                  ]
                }
                """;

        Set<SimulatorPermission> perms = computer.compute(List.of(policyJson));
        assertTrue(perms.isEmpty(), "Deny-only policy must produce zero expected permissions");
    }

    @Test
    void disabledPolicyProducesNoPermissions() {
        String policyJson = """
                {
                  "id": 3,
                  "service": "lakeformation",
                  "isEnabled": false,
                  "resources": {
                    "database": { "values": ["analytics"] },
                    "table":    { "values": ["events"] }
                  },
                  "policyItems": [
                    { "users": ["analyst"], "accesses": [{"type": "select", "isAllowed": true}],
                      "delegateAdmin": false }
                  ]
                }
                """;

        Set<SimulatorPermission> perms = computer.compute(List.of(policyJson));
        assertTrue(perms.isEmpty(), "Disabled policy must produce zero expected permissions");
    }

    @Test
    void unmappedUserProducesNoPermissions() {
        String policyJson = """
                {
                  "id": 4,
                  "service": "lakeformation",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["analytics"] },
                    "table":    { "values": ["events"] }
                  },
                  "policyItems": [
                    { "users": ["unknown_user"], "accesses": [{"type": "select", "isAllowed": true}],
                      "delegateAdmin": false }
                  ]
                }
                """;

        Set<SimulatorPermission> perms = computer.compute(List.of(policyJson));
        assertTrue(perms.isEmpty(), "Unmapped user must produce zero expected permissions");
    }

    @Test
    void allowAndDenyOnSameResourceProducesZeroPermissions() {
        // Cross-policy: one allows, one denies same user+resource
        String allowPolicy = """
                { "id": 5, "service": "lakeformation", "isEnabled": true,
                  "resources": { "database": { "values": ["db"] }, "table": { "values": ["tbl"] } },
                  "policyItems": [{ "users": ["analyst"],
                                    "accesses": [{"type": "select", "isAllowed": true}],
                                    "delegateAdmin": false }] }
                """;
        String denyPolicy = """
                { "id": 6, "service": "lakeformation", "isEnabled": true,
                  "resources": { "database": { "values": ["db"] }, "table": { "values": ["tbl"] } },
                  "policyItems": [],
                  "denyPolicyItems": [{ "users": ["analyst"],
                                        "accesses": [{"type": "select", "isAllowed": true}] }] }
                """;

        Set<SimulatorPermission> perms = computer.compute(List.of(allowPolicy, denyPolicy));
        long selectForAnalyst = perms.stream()
                .filter(p -> p.getPrincipalArn().contains("AnalystRole"))
                .filter(p -> p.getPermissions().contains("SELECT"))
                .count();
        assertEquals(0, selectForAnalyst,
                "Deny overrides allow — zero expected SELECT permissions for analyst");
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
mvn test -pl simulator -Dtest=ExpectedPermissionsComputerTest -q 2>&1 | tail -10
```

- [ ] **Step 3: Create `ExpectedPermissionsComputer.java`**

This class parses raw Ranger policy JSON strings using only Jackson — zero imports from the main pipeline. Implement:
- Parse `isEnabled` — skip disabled policies
- Parse `policyItems` (allow) and `denyPolicyItems` (deny)
- For each allow item: resolve users to ARN via constructor-injected mapping; expand access types to LF permissions; compute allow-minus-deny for each `(user, resource, action)` triple
- Map Ranger access types to LF permission names: `select→SELECT`, `insert→INSERT`, `update→INSERT`, `delete→DELETE`, `alter→ALTER`, `drop→DROP`, `describe→DESCRIBE`, `create→CREATE_TABLE`, `all→{SELECT,INSERT,DELETE,ALTER,DROP,DESCRIBE}`, `uri→DATA_LOCATION_ACCESS`
- For EMRFS service policies: map to `SimulatorPermission.s3AgPermission`
- `delegateAdmin: true` → `grantable=true`
- Disabled policies, deny-only policies, and all-unmapped-principal policies → empty set

The full implementation is non-trivial (~200 lines). Write it to pass the tests above. Consult the main `PolicyConverter.java` and `RangerToCedarConverter.java` for the access-type mapping and deny-suppression logic, but re-implement independently.

- [ ] **Step 4: Run tests until all pass**

```bash
mvn test -pl simulator -Dtest=ExpectedPermissionsComputerTest -q 2>&1 | tail -20
```

Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/ExpectedPermissionsComputer.java \
        simulator/src/test/java/com/amazonaws/policyconverters/simulator/ExpectedPermissionsComputerTest.java
git commit -m "feat(simulator): add independent ExpectedPermissionsComputer with unit tests"
```

---

## Task 9: Phase1DriftValidator and Phase2CorrectnessValidator

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/Phase1DriftValidator.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/Phase2CorrectnessValidator.java`

- [ ] **Step 1: Create `Phase1DriftValidator.java`**

```java
package com.amazonaws.policyconverters.simulator.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 1: compares the sync service's checkpoint against actual state.
 *
 * Scope: Phase 1 only covers S3AG permissions (checkpoint stores them verbatim).
 * LF permissions are NOT stored in the checkpoint (only Cedar text is stored),
 * so LF drift detection is handled exclusively by Phase 2 (ExpectedPermissionsComputer).
 *
 * Detects drift between what the sync service last wrote and what S3AG currently holds.
 * Does NOT detect conversion logic bugs — that is Phase 2's role.
 */
public class Phase1DriftValidator {
    private static final Logger LOG = LoggerFactory.getLogger(Phase1DriftValidator.class);

    private final LFPermissionsFetcher lfFetcher;
    private final S3AgPermissionsFetcher s3agFetcher;
    private final Path checkpointFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public Phase1DriftValidator(LFPermissionsFetcher lfFetcher,
                                 S3AgPermissionsFetcher s3agFetcher,
                                 Path checkpointFile) {
        this.lfFetcher = lfFetcher;
        this.s3agFetcher = s3agFetcher;
        this.checkpointFile = checkpointFile;
    }

    public S3AgPermissionsFetcher getS3agFetcher() { return s3agFetcher; }

    public ValidationResult validate() throws Exception {
        // Phase 1 only compares S3AG state — the checkpoint does not store LF permissions.
        // LF correctness is covered entirely by Phase 2 (ExpectedPermissionsComputer).
        Set<SimulatorPermission> s3agActual = s3agFetcher.fetchAll();
        Set<SimulatorPermission> checkpointS3ag = loadCheckpointPermissions();

        Set<SimulatorPermission> overGrants = new HashSet<>(s3agActual);
        overGrants.removeAll(checkpointS3ag);   // in actual but not in checkpoint

        Set<SimulatorPermission> underGrants = new HashSet<>(checkpointS3ag);
        underGrants.removeAll(s3agActual);      // in checkpoint but not in actual

        if (overGrants.isEmpty() && underGrants.isEmpty()) {
            LOG.info("Phase1: PASS — S3AG actual state matches checkpoint ({} grants)",
                    s3agActual.size());
            return ValidationResult.pass();
        }
        LOG.warn("Phase1: VIOLATION — S3AG drift detected: over-grants={}, under-grants={}",
                overGrants.size(), underGrants.size());
        return ValidationResult.violation(overGrants, underGrants);
    }

    private Set<SimulatorPermission> loadCheckpointPermissions() throws Exception {
        // The checkpoint at checkpointFile is written by CheckpointStore. Its JSON structure:
        //   { "cedarPolicyText": "...", "s3AgOperations": [...], "policyVersion": N, ... }
        //
        // Phase 1 can only verify S3AG operations (they are stored verbatim in the checkpoint).
        // LF permissions are NOT stored in the checkpoint — only Cedar text is stored.
        // Therefore Phase 1 covers S3AG drift; Phase 2 covers all LF + S3AG correctness.
        if (!Files.exists(checkpointFile)) {
            LOG.info("Phase1: no checkpoint file at {}, skipping drift check", checkpointFile);
            return Set.of();
        }
        JsonNode root = mapper.readTree(checkpointFile.toFile());
        Set<SimulatorPermission> result = new HashSet<>();
        JsonNode s3AgOps = root.path("s3AgOperations");
        if (s3AgOps.isArray()) {
            for (JsonNode op : s3AgOps) {
                String principalArn = op.path("principalArn").asText(null);
                String s3Prefix = op.path("s3Prefix").asText(null);
                String permission = op.path("permission").asText(null);
                if (principalArn != null && s3Prefix != null && permission != null) {
                    result.add(SimulatorPermission.s3AgPermission(principalArn, s3Prefix, permission));
                }
            }
        }
        LOG.info("Phase1: loaded {} S3AG permissions from checkpoint", result.size());
        return result;
    }
}
```

- [ ] **Step 2: Create `Phase2CorrectnessValidator.java`**

```java
package com.amazonaws.policyconverters.simulator.validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 2: independently computes expected LF + S3AG permissions from current
 * Ranger policies, then compares against actual state. Detects conversion logic
 * bugs that Phase 1 would miss (since Phase 1 trusts the checkpoint).
 */
public class Phase2CorrectnessValidator {
    private static final Logger LOG = LoggerFactory.getLogger(Phase2CorrectnessValidator.class);

    private final ExpectedPermissionsComputer computer;
    private final LFPermissionsFetcher lfFetcher;
    private final S3AgPermissionsFetcher s3agFetcher;
    private final RangerPoliciesProvider policiesProvider;

    public interface RangerPoliciesProvider {
        List<String> fetchCurrentPoliciesAsJson() throws Exception;
    }

    public Phase2CorrectnessValidator(ExpectedPermissionsComputer computer,
                                       LFPermissionsFetcher lfFetcher,
                                       S3AgPermissionsFetcher s3agFetcher,
                                       RangerPoliciesProvider policiesProvider) {
        this.computer = computer;
        this.lfFetcher = lfFetcher;
        this.s3agFetcher = s3agFetcher;
        this.policiesProvider = policiesProvider;
    }

    public ExpectedPermissionsComputer getComputer() { return computer; }
    public LFPermissionsFetcher getLfFetcher() { return lfFetcher; }

    public ValidationResult validate() throws Exception {
        List<String> currentPolicies = policiesProvider.fetchCurrentPoliciesAsJson();
        Set<SimulatorPermission> expected = computer.compute(currentPolicies);

        Set<SimulatorPermission> actual = new HashSet<>();
        actual.addAll(lfFetcher.fetchAll());
        actual.addAll(s3agFetcher.fetchAll());

        Set<SimulatorPermission> overGrants = new HashSet<>(actual);
        overGrants.removeAll(expected);

        Set<SimulatorPermission> underGrants = new HashSet<>(expected);
        underGrants.removeAll(actual);

        if (overGrants.isEmpty() && underGrants.isEmpty()) {
            LOG.info("Phase2: PASS — actual state matches independently computed expected state. " +
                    "policies={}, expected-permissions={}", currentPolicies.size(), expected.size());
            return ValidationResult.pass();
        }
        LOG.warn("Phase2: VIOLATION — over-grants={}, under-grants={}, " +
                "policies={}, expected-permissions={}",
                overGrants.size(), underGrants.size(), currentPolicies.size(), expected.size());
        return ValidationResult.violation(overGrants, underGrants);
    }
}
```

Note: `RangerPoliciesProvider` is defined as an interface here; `RangerPolicyClient` implements it by wrapping `getPoliciesByService()` and serializing each `JsonNode` back to a string.

- [ ] **Step 3: Compile**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

- [ ] **Step 4: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/validator/
git commit -m "feat(simulator): add Phase1DriftValidator and Phase2CorrectnessValidator"
```

---

## Task 10: ReproductionBundle, BundleWriter, and RemediationRunner

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/remediation/ReproductionBundle.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/remediation/BundleWriter.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/remediation/RemediationRunner.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/alert/AlertEmitter.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/alert/LogFileAlertEmitter.java`

- [ ] **Step 1: Create `ReproductionBundle.java`**

```java
package com.amazonaws.policyconverters.simulator.remediation;

import com.amazonaws.policyconverters.simulator.validator.ValidationResult;

import java.nio.file.Path;

public record ReproductionBundle(
        long cycleNumber,
        String timestamp,
        String mutationLogPath,
        String rangerSnapshotJson,
        String lfActualJson,
        String lfExpectedJson,
        String diffJson,
        String syncServiceLogSnippet,
        ValidationResult phase1Result,
        ValidationResult phase2Result
) {}
```

- [ ] **Step 2: Create `BundleWriter.java`**

```java
package com.amazonaws.policyconverters.simulator.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

public class BundleWriter {
    private static final Logger LOG = LoggerFactory.getLogger(BundleWriter.class);
    private final Path outputRoot;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public BundleWriter(Path outputRoot) {
        this.outputRoot = outputRoot;
    }

    public Path write(ReproductionBundle bundle) throws IOException {
        String timestamp = Instant.now().toString().replace(":", "-");
        Path bundleDir = outputRoot.resolve("reproduction-bundles").resolve(timestamp);
        Files.createDirectories(bundleDir);

        Files.writeString(bundleDir.resolve("mutations.json"), bundle.mutationLogPath());
        Files.writeString(bundleDir.resolve("ranger-snapshot.json"), bundle.rangerSnapshotJson());
        Files.writeString(bundleDir.resolve("lf-actual.json"), bundle.lfActualJson());
        Files.writeString(bundleDir.resolve("lf-expected.json"), bundle.lfExpectedJson());
        Files.writeString(bundleDir.resolve("diff.json"), bundle.diffJson());
        Files.writeString(bundleDir.resolve("sync-service.log"), bundle.syncServiceLogSnippet());
        Files.writeString(bundleDir.resolve("cycle-sequence.json"), String.format(
                "{\"violationDetectedAfterCycle\":%d}", bundle.cycleNumber()));
        Files.writeString(bundleDir.resolve("README.txt"), buildReadme(bundle, bundleDir));

        LOG.info("Reproduction bundle written to {}", bundleDir);
        return bundleDir;
    }

    private String buildReadme(ReproductionBundle bundle, Path bundleDir) {
        return String.format("""
                Violation detected at cycle %d (%s)

                Phase 1 (drift): %s
                Phase 2 (correctness): %s

                To reproduce:
                1. Restore Ranger policies from ranger-snapshot.json
                2. Clear all LF permissions for the test principal pool
                3. Start the sync service (clean state)
                4. Wait for one full sync cycle
                5. Compare lf-expected.json against actual ListPermissions output

                Files in this bundle:
                - mutations.json      : all mutations applied during the run
                - ranger-snapshot.json: Ranger state at time of violation
                - lf-actual.json      : what LF actually contained
                - lf-expected.json    : what LF should have contained
                - diff.json           : over-grants and under-grants
                - sync-service.log    : sync service logs at time of violation
                """,
                bundle.cycleNumber(), bundle.timestamp(),
                bundle.phase1Result().status(), bundle.phase2Result().status());
    }
}
```

- [ ] **Step 3: Create `AlertEmitter.java` and `LogFileAlertEmitter.java`**

```java
// AlertEmitter.java
package com.amazonaws.policyconverters.simulator.alert;

import java.nio.file.Path;

public interface AlertEmitter {
    enum ViolationType { TRANSIENT, PERSISTENT }
    void emit(ViolationType type, Path bundlePath, String details);
}
```

```java
// LogFileAlertEmitter.java
package com.amazonaws.policyconverters.simulator.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class LogFileAlertEmitter implements AlertEmitter {
    private static final Logger LOG = LoggerFactory.getLogger(LogFileAlertEmitter.class);

    @Override
    public void emit(ViolationType type, Path bundlePath, String details) {
        if (type == ViolationType.PERSISTENT) {
            LOG.error("PERSISTENT_VIOLATION: {} | bundle: {}", details, bundlePath);
        } else {
            LOG.warn("TRANSIENT_VIOLATION (self-healed): {} | bundle: {}", details, bundlePath);
        }
    }
}
```

- [ ] **Step 4: Create `RemediationRunner.java`**

```java
package com.amazonaws.policyconverters.simulator.remediation;

import com.amazonaws.policyconverters.simulator.status.CycleWaiter;
import com.amazonaws.policyconverters.simulator.status.SyncServiceStatusClient;
import com.amazonaws.policyconverters.simulator.validator.Phase1DriftValidator;
import com.amazonaws.policyconverters.simulator.validator.Phase2CorrectnessValidator;
import com.amazonaws.policyconverters.simulator.validator.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemediationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RemediationRunner.class);

    private final SyncServiceStatusClient statusClient;
    private final CycleWaiter cycleWaiter;
    private final Phase1DriftValidator phase1;
    private final Phase2CorrectnessValidator phase2;

    public RemediationRunner(SyncServiceStatusClient statusClient,
                              CycleWaiter cycleWaiter,
                              Phase1DriftValidator phase1,
                              Phase2CorrectnessValidator phase2) {
        this.statusClient = statusClient;
        this.cycleWaiter = cycleWaiter;
        this.phase1 = phase1;
        this.phase2 = phase2;
    }

    public enum RemediationOutcome { SELF_HEALED, PERSISTENT }

    /**
     * Wait for one more sync cycle, then re-validate. Return whether the
     * violation self-healed or persists.
     */
    public RemediationOutcome run() throws Exception {
        SyncServiceStatusClient.StatusSnapshot before = statusClient.getStatus();
        LOG.info("Remediation: waiting for cycle > {} to complete", before.lastCompletedCycle());

        CycleWaiter.WaitResult waitResult = cycleWaiter.waitUntilSettled(
                before.lastCompletedCycle(),
                before.lastCompletedWildcardRefreshCycle());

        if (waitResult == CycleWaiter.WaitResult.TIMEOUT) {
            LOG.warn("Remediation: cycle wait timed out — treating as PERSISTENT");
            return RemediationOutcome.PERSISTENT;
        }

        ValidationResult p1 = phase1.validate();
        ValidationResult p2 = phase2.validate();

        if (!p1.isViolation() && !p2.isViolation()) {
            LOG.info("Remediation: SELF_HEALED — violation resolved after one more cycle");
            return RemediationOutcome.SELF_HEALED;
        }
        LOG.warn("Remediation: PERSISTENT — violation remains after remediation cycle");
        return RemediationOutcome.PERSISTENT;
    }
}
```

- [ ] **Step 5: Compile**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/remediation/ \
        simulator/src/main/java/com/amazonaws/policyconverters/simulator/alert/
git commit -m "feat(simulator): add ReproductionBundle, BundleWriter, RemediationRunner, AlertEmitter"
```

---

## Task 11: MutationDriver and SimulatorMain (Wiring)

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/MutationDriver.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/SimulatorMain.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/SimulatorCleanup.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/HivePolicyGenerator.java`

- [ ] **Step 1: Create `HivePolicyGenerator.java`**

Generates random Hive/Trino-style Ranger policy JSON for the configured databases and principal pool. Takes a `SimulatorConfig` and a `Random`. Key method: `String generateCreatePayload(long id)` — returns a JSON string ready to POST to Ranger Admin.

```java
package com.amazonaws.policyconverters.simulator.workload;

import com.amazonaws.policyconverters.simulator.driver.SimulatorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

public class HivePolicyGenerator {
    private static final List<String> ACCESS_TYPES = List.of("select", "insert", "delete", "alter", "drop");
    private final SimulatorConfig config;
    private final Random random;
    private final ObjectMapper mapper = new ObjectMapper();

    public HivePolicyGenerator(SimulatorConfig config, Random random) {
        this.config = config;
        this.random = random;
    }

    public String generateCreatePayload(long policyId, List<String> tables) {
        // Pick random database and 1-3 random tables
        List<String> dbs = config.glueDatabases();
        String db = dbs.get(random.nextInt(dbs.size()));

        // Pick 1-3 random principals from the pool
        List<String> principalNames = new ArrayList<>(config.principalPool().keySet());
        Collections.shuffle(principalNames, random);
        List<String> selectedPrincipals = principalNames.subList(0, 1 + random.nextInt(
                Math.min(3, principalNames.size())));

        // Pick 1-3 random access types
        List<String> selectedAccesses = new ArrayList<>();
        int numAccesses = 1 + random.nextInt(3);
        List<String> shuffledAccesses = new ArrayList<>(ACCESS_TYPES);
        Collections.shuffle(shuffledAccesses, random);
        shuffledAccesses.subList(0, numAccesses).forEach(a ->
                selectedAccesses.add(a));

        // Whether this is a deny policy (~20% chance)
        boolean isDeny = random.nextInt(100) < 20;

        ObjectNode policy = mapper.createObjectNode();
        policy.put("service", config.rangerServiceName());
        policy.put("name", "sim-hive-" + policyId);
        policy.put("isEnabled", true);

        // Resources
        ObjectNode resources = mapper.createObjectNode();
        ObjectNode dbRes = mapper.createObjectNode();
        ArrayNode dbValues = mapper.createArrayNode().add(db);
        dbRes.set("values", dbValues);
        dbRes.put("isExcludes", false);
        dbRes.put("isRecursive", false);
        resources.set("database", dbRes);

        // Pick a table or wildcard
        ObjectNode tableRes = mapper.createObjectNode();
        boolean isWildcard = random.nextInt(100) < 15;  // 15% wildcard
        ArrayNode tableValues = mapper.createArrayNode().add(isWildcard ? "*" :
                (tables.isEmpty() ? "test_table" : tables.get(random.nextInt(tables.size()))));
        tableRes.set("values", tableValues);
        tableRes.put("isExcludes", false);
        tableRes.put("isRecursive", false);
        resources.set("table", tableRes);
        policy.set("resources", resources);

        // Policy item
        ArrayNode accesses = mapper.createArrayNode();
        selectedAccesses.forEach(a -> {
            ObjectNode access = mapper.createObjectNode();
            access.put("type", a);
            access.put("isAllowed", true);
            accesses.add(access);
        });
        ObjectNode item = mapper.createObjectNode();
        item.set("accesses", accesses);
        item.set("users", mapper.createArrayNode().addAll(
                selectedPrincipals.stream().map(TextNode::new).toList()));
        item.set("groups", mapper.createArrayNode());
        item.set("roles", mapper.createArrayNode());
        item.set("conditions", mapper.createArrayNode());
        item.put("delegateAdmin", random.nextInt(100) < 5);  // 5% delegateAdmin

        ArrayNode emptyArray = mapper.createArrayNode();
        if (isDeny) {
            policy.set("policyItems", emptyArray);
            policy.set("denyPolicyItems", mapper.createArrayNode().add(item));
        } else {
            policy.set("policyItems", mapper.createArrayNode().add(item));
            policy.set("denyPolicyItems", emptyArray);
        }

        try {
            return mapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize policy", e);
        }
    }
}
```

- [ ] **Step 2: Create `MutationDriver.java`**

The main loop. Implements the simulator flow from the spec:

```java
package com.amazonaws.policyconverters.simulator.driver;

import com.amazonaws.policyconverters.simulator.alert.AlertEmitter;
import com.amazonaws.policyconverters.simulator.remediation.BundleWriter;
import com.amazonaws.policyconverters.simulator.remediation.RemediationRunner;
import com.amazonaws.policyconverters.simulator.remediation.ReproductionBundle;
import com.amazonaws.policyconverters.simulator.status.CycleWaiter;
import com.amazonaws.policyconverters.simulator.status.SyncServiceStatusClient;
import com.amazonaws.policyconverters.simulator.validator.*;
import com.amazonaws.policyconverters.simulator.workload.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class MutationDriver {
    private static final Logger LOG = LoggerFactory.getLogger(MutationDriver.class);

    private final SimulatorConfig config;
    private final RangerPolicyClient rangerClient;
    private final SyncServiceStatusClient statusClient;
    private final CycleWaiter cycleWaiter;
    private final WorkloadOrchestrator orchestrator;
    private final HivePolicyGenerator hiveGenerator;
    private final MutationLog mutationLog;
    private final Phase1DriftValidator phase1;
    private final Phase2CorrectnessValidator phase2;
    private final RemediationRunner remediationRunner;
    private final BundleWriter bundleWriter;
    private final AlertEmitter alertEmitter;
    private final List<Long> activePolicyIds = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public MutationDriver(SimulatorConfig config,
                          RangerPolicyClient rangerClient,
                          SyncServiceStatusClient statusClient,
                          CycleWaiter cycleWaiter,
                          WorkloadOrchestrator orchestrator,
                          HivePolicyGenerator hiveGenerator,
                          MutationLog mutationLog,
                          Phase1DriftValidator phase1,
                          Phase2CorrectnessValidator phase2,
                          RemediationRunner remediationRunner,
                          BundleWriter bundleWriter,
                          AlertEmitter alertEmitter) {
        this.config = config;
        this.rangerClient = rangerClient;
        this.statusClient = statusClient;
        this.cycleWaiter = cycleWaiter;
        this.orchestrator = orchestrator;
        this.hiveGenerator = hiveGenerator;
        this.mutationLog = mutationLog;
        this.phase1 = phase1;
        this.phase2 = phase2;
        this.remediationRunner = remediationRunner;
        this.bundleWriter = bundleWriter;
        this.alertEmitter = alertEmitter;
    }

    public void run() throws Exception {
        long startMs = System.currentTimeMillis();
        long cycleNumber = 0;

        while (!isRunComplete(startMs)) {
            cycleNumber++;
            LOG.info("=== Simulator cycle {} starting ===", cycleNumber);

            // Step 1: snapshot current status counters
            SyncServiceStatusClient.StatusSnapshot before = statusClient.getStatus();
            long N = before.lastCompletedCycle();
            long W = before.lastCompletedWildcardRefreshCycle();

            // Step 2 + 3: generate and apply mutations
            List<MutationOperation> batch = orchestrator.nextBatch();
            List<Map<String, Object>> payloads = new ArrayList<>();
            applyBatch(cycleNumber, batch, payloads);

            // Step 4: log mutations
            mutationLog.append(cycleNumber, batch, payloads);

            // Step 5: wait for sync service to settle
            CycleWaiter.WaitResult waitResult = cycleWaiter.waitUntilSettled(N, W);
            if (waitResult == CycleWaiter.WaitResult.TIMEOUT) {
                LOG.warn("Cycle {}: wait timeout — skipping validation", cycleNumber);
                continue;
            }

            // Step 6 + 7: validate
            ValidationResult p1Result = phase1.validate();
            ValidationResult p2Result = phase2.validate();

            if (!p1Result.isViolation() && !p2Result.isViolation()) {
                LOG.info("Cycle {}: PASS", cycleNumber);
                continue;
            }

            // Step 9: violation handling
            LOG.warn("Cycle {}: VIOLATION detected — writing bundle and attempting remediation", cycleNumber);
            Path bundlePath = bundleWriter.write(buildBundle(cycleNumber, p1Result, p2Result));
            RemediationRunner.RemediationOutcome outcome = remediationRunner.run();

            if (outcome == RemediationRunner.RemediationOutcome.SELF_HEALED) {
                alertEmitter.emit(AlertEmitter.ViolationType.TRANSIENT, bundlePath,
                        "Cycle " + cycleNumber + ": " + p2Result.details());
            } else {
                alertEmitter.emit(AlertEmitter.ViolationType.PERSISTENT, bundlePath,
                        "Cycle " + cycleNumber + ": " + p2Result.details());
            }
        }
        LOG.info("Simulator run complete after {} cycles", cycleNumber);
    }

    private void applyBatch(long cycleNumber, List<MutationOperation> batch,
                             List<Map<String, Object>> payloads) throws Exception {
        for (MutationOperation op : batch) {
            switch (op) {
                case CREATE -> {
                    String json = hiveGenerator.generateCreatePayload(
                            cycleNumber * 1000 + payloads.size(), List.of());
                    long id = rangerClient.createPolicy(json);
                    activePolicyIds.add(id);
                    payloads.add(Map.of("op", "CREATE", "policyId", id, "json", json));
                }
                case DELETE -> {
                    if (!activePolicyIds.isEmpty()) {
                        long id = activePolicyIds.remove(0);
                        rangerClient.deletePolicy(id);
                        payloads.add(Map.of("op", "DELETE", "policyId", id));
                    }
                }
                case DISABLE -> {
                    if (!activePolicyIds.isEmpty()) {
                        long id = activePolicyIds.get(0);
                        // Fetch current policy, set isEnabled=false, PUT back
                        List<com.fasterxml.jackson.databind.JsonNode> policies =
                                rangerClient.getPoliciesByService(config.rangerServiceName());
                        policies.stream()
                                .filter(p -> p.get("id").asLong() == id)
                                .findFirst()
                                .ifPresent(p -> {
                                    try {
                                        com.fasterxml.jackson.databind.node.ObjectNode updated =
                                                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(p.toString());
                                        updated.put("isEnabled", false);
                                        rangerClient.updatePolicy(id, mapper.writeValueAsString(updated));
                                        payloads.add(Map.of("op", "DISABLE", "policyId", id));
                                    } catch (Exception e) {
                                        LOG.warn("DISABLE failed for policy {}: {}", id, e.getMessage());
                                    }
                                });
                    }
                }
                case ENABLE -> {
                    if (!activePolicyIds.isEmpty()) {
                        long id = activePolicyIds.get(0);
                        List<com.fasterxml.jackson.databind.JsonNode> policies =
                                rangerClient.getPoliciesByService(config.rangerServiceName());
                        policies.stream()
                                .filter(p -> p.get("id").asLong() == id)
                                .findFirst()
                                .ifPresent(p -> {
                                    try {
                                        com.fasterxml.jackson.databind.node.ObjectNode updated =
                                                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(p.toString());
                                        updated.put("isEnabled", true);
                                        rangerClient.updatePolicy(id, mapper.writeValueAsString(updated));
                                        payloads.add(Map.of("op", "ENABLE", "policyId", id));
                                    } catch (Exception e) {
                                        LOG.warn("ENABLE failed for policy {}: {}", id, e.getMessage());
                                    }
                                });
                    }
                }
                case UPDATE -> {
                    if (!activePolicyIds.isEmpty()) {
                        long id = activePolicyIds.get(0);
                        // Add one random principal from pool to the first policy item
                        List<com.fasterxml.jackson.databind.JsonNode> policies =
                                rangerClient.getPoliciesByService(config.rangerServiceName());
                        policies.stream()
                                .filter(p -> p.get("id").asLong() == id)
                                .findFirst()
                                .ifPresent(p -> {
                                    try {
                                        com.fasterxml.jackson.databind.node.ObjectNode updated =
                                                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(p.toString());
                                        // Add a new random principal to policyItems[0].users
                                        List<String> pool = new ArrayList<>(config.principalPool().keySet());
                                        String newUser = pool.get(new Random().nextInt(pool.size()));
                                        com.fasterxml.jackson.databind.JsonNode items = updated.path("policyItems");
                                        if (items.isArray() && !items.isEmpty()) {
                                            com.fasterxml.jackson.databind.node.ArrayNode users =
                                                    (com.fasterxml.jackson.databind.node.ArrayNode) items.get(0).get("users");
                                            if (users != null) users.add(newUser);
                                        }
                                        rangerClient.updatePolicy(id, mapper.writeValueAsString(updated));
                                        payloads.add(Map.of("op", "UPDATE", "policyId", id, "addedUser", newUser));
                                    } catch (Exception e) {
                                        LOG.warn("UPDATE failed for policy {}: {}", e.getMessage());
                                    }
                                });
                    }
                }
            }
        }
    }

    private boolean isRunComplete(long startMs) {
        if (config.runDurationMs() == SimulatorConfig.INDEFINITE) return false;
        return System.currentTimeMillis() - startMs >= config.runDurationMs();
    }

    private ReproductionBundle buildBundle(long cycleNumber,
                                            ValidationResult p1, ValidationResult p2) {
        String rangerSnapshot = "{}";
        String lfActual = "{}";
        String lfExpected = "{}";
        try {
            // Ranger snapshot
            rangerSnapshot = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(rangerClient.getPoliciesByService(config.rangerServiceName()));
        } catch (Exception e) {
            LOG.warn("Failed to capture ranger snapshot for bundle: {}", e.getMessage());
        }
        try {
            // LF + S3AG actual state — use phase2's fetchers (they hold both LF and S3AG)
            Set<SimulatorPermission> actual = new java.util.HashSet<>();
            actual.addAll(phase2.getLfFetcher().fetchAll());
            actual.addAll(phase1.getS3agFetcher().fetchAll());
            lfActual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual);
        } catch (Exception e) {
            LOG.warn("Failed to capture LF actual for bundle: {}", e.getMessage());
        }
        try {
            // LF + S3AG expected state
            List<String> policies = rangerClient.getPoliciesByService(config.rangerServiceName())
                    .stream().map(Object::toString).toList();
            Set<SimulatorPermission> expected = phase2.getComputer().compute(policies);
            lfExpected = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(expected);
        } catch (Exception e) {
            LOG.warn("Failed to capture LF expected for bundle: {}", e.getMessage());
        }
        String diff = mapper.createObjectNode()
                .put("overGrants", p2.overGrants().size())
                .put("underGrants", p2.underGrants().size()).toString();
        return new ReproductionBundle(
                cycleNumber,
                Instant.now().toString(),
                config.mutationLogPath(),
                rangerSnapshot,
                lfActual,
                lfExpected,
                diff,
                "",  // sync service log — not accessible from simulator process
                p1, p2);
    }
}
```

- [ ] **Step 3: Create `SimulatorMain.java`**

Startup assertions + wiring + run:

```java
package com.amazonaws.policyconverters.simulator.driver;

import com.amazonaws.policyconverters.simulator.alert.LogFileAlertEmitter;
import com.amazonaws.policyconverters.simulator.remediation.*;
import com.amazonaws.policyconverters.simulator.status.*;
import com.amazonaws.policyconverters.simulator.validator.*;
import com.amazonaws.policyconverters.simulator.workload.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lakeformation.LakeFormationClient;
import software.amazon.awssdk.services.s3control.S3ControlClient;

import java.nio.file.Paths;
import java.util.List;

public class SimulatorMain {
    private static final Logger LOG = LoggerFactory.getLogger(SimulatorMain.class);

    public static void main(String[] args) throws Exception {
        SimulatorConfig config = buildConfigFromEnv();

        try (RangerPolicyClient rangerClient = new RangerPolicyClient(
                config.rangerAdminUrl(), config.rangerAdminUser(), config.rangerAdminPassword())) {

            // AWS clients (needed for startup assertions)
            LakeFormationClient lfClientForStartup = LakeFormationClient.builder()
                    .region(Region.of(config.awsRegion())).build();
            S3ControlClient s3agClientForStartup = S3ControlClient.builder()
                    .region(Region.of(config.awsRegion())).build();
            LFPermissionsFetcher lfFetcherForStartup =
                    new LFPermissionsFetcher(lfClientForStartup, config.awsAccountId());
            S3AgPermissionsFetcher s3agFetcherForStartup =
                    new S3AgPermissionsFetcher(s3agClientForStartup,
                            config.awsAccountId(), config.s3AgInstanceArn());

            // Status client and cycle waiter needed for startup assertions
            SyncServiceStatusClient statusClientForStartup =
                    new SyncServiceStatusClient(config.syncServiceStatusUrl());
            CycleWaiter cycleWaiterForStartup =
                    new CycleWaiter(statusClientForStartup, config.cycleWaiterTimeoutMs());

            // Startup assertions
            runStartupAssertions(config, rangerClient,
                    lfFetcherForStartup, s3agFetcherForStartup,
                    statusClientForStartup, cycleWaiterForStartup);

            // Reuse AWS clients from startup assertions
            LFPermissionsFetcher lfFetcher = lfFetcherForStartup;
            S3AgPermissionsFetcher s3agFetcher = s3agFetcherForStartup;
            ExpectedPermissionsComputer computer = new ExpectedPermissionsComputer(
                    config.awsAccountId(), config.awsRegion(),
                    config.principalPool(), config.s3AgInstanceArn());

            SyncServiceStatusClient statusClient = new SyncServiceStatusClient(config.syncServiceStatusUrl());
            CycleWaiter cycleWaiter = new CycleWaiter(statusClient, config.cycleWaiterTimeoutMs());

            WorkloadOrchestrator orchestrator = WorkloadOrchestrator.defaultWeights(config.maxBatchSize());
            HivePolicyGenerator hiveGenerator = new HivePolicyGenerator(config, new java.util.Random());
            MutationLog mutationLog = new MutationLog(Paths.get(config.mutationLogPath()));

            Phase2CorrectnessValidator.RangerPoliciesProvider policiesProvider =
                    () -> rangerClient.getPoliciesByService(config.rangerServiceName())
                            .stream().map(Object::toString).toList();

            Phase1DriftValidator phase1 = new Phase1DriftValidator(
                    lfFetcher, s3agFetcher, Paths.get(config.checkpointFilePath()));
            Phase2CorrectnessValidator phase2 = new Phase2CorrectnessValidator(
                    computer, lfFetcher, s3agFetcher, policiesProvider);

            BundleWriter bundleWriter = new BundleWriter(Paths.get(config.outputDirectory()));
            RemediationRunner remediationRunner = new RemediationRunner(
                    statusClient, cycleWaiter, phase1, phase2);

            MutationDriver driver = new MutationDriver(
                    config, rangerClient, statusClient, cycleWaiter,
                    orchestrator, hiveGenerator, mutationLog,
                    phase1, phase2, remediationRunner,
                    bundleWriter, new LogFileAlertEmitter());

            driver.run();
        }
    }

    static void runStartupAssertions(SimulatorConfig config,
                                     RangerPolicyClient rangerClient,
                                     LFPermissionsFetcher lfFetcher,
                                     S3AgPermissionsFetcher s3agFetcher,
                                     SyncServiceStatusClient statusClient,
                                     CycleWaiter cycleWaiter) throws Exception {
        LOG.info("Running startup assertions...");

        // 1. Ranger must have zero policies for the service name
        List<?> existingPolicies = rangerClient.getPoliciesByService(config.rangerServiceName());
        if (!existingPolicies.isEmpty()) {
            LOG.error("DIRTY_STARTUP: Ranger Admin has {} existing policies for service '{}'. " +
                    "Run SimulatorCleanup before starting.", existingPolicies.size(), config.rangerServiceName());
            System.exit(1);
        }

        // 2. LF must have zero permissions for the entire catalog (not scoped to pool).
        //    Scoping to the pool would miss PrincipalMapper bugs that assign unexpected ARNs.
        Set<SimulatorPermission> lfPerms = lfFetcher.fetchAll();
        if (!lfPerms.isEmpty()) {
            LOG.error("DIRTY_STARTUP: Lake Formation already has {} permissions. " +
                    "Run SimulatorCleanup and revoke all LF permissions before starting. " +
                    "First 5: {}", lfPerms.size(),
                    lfPerms.stream().limit(5).toList());
            System.exit(1);
        }

        // 3. S3AG must have zero grants
        Set<SimulatorPermission> s3agPerms = s3agFetcher.fetchAll();
        if (!s3agPerms.isEmpty()) {
            LOG.error("DIRTY_STARTUP: S3 Access Grants already has {} grants. " +
                    "Revoke all access grants before starting.", s3agPerms.size());
            System.exit(1);
        }

        // 4. Wait for one full sync cycle so the sync service processes any stale checkpoint.
        //    Then re-assert LF and S3AG are still empty — a replayed checkpoint would show up here.
        LOG.info("Startup: waiting for one full sync cycle to clear any stale checkpoint state...");
        SyncServiceStatusClient.StatusSnapshot before = statusClient.getStatus();
        CycleWaiter.WaitResult waitResult = cycleWaiter.waitUntilSettled(
                before.lastCompletedCycle(), before.lastCompletedWildcardRefreshCycle());
        if (waitResult == CycleWaiter.WaitResult.TIMEOUT) {
            LOG.error("DIRTY_STARTUP: Sync service did not complete a cycle during startup assertions.");
            System.exit(1);
        }

        // 5. Re-assert empty after the cycle
        Set<SimulatorPermission> lfPermsAfterCycle = lfFetcher.fetchAll();
        Set<SimulatorPermission> s3agPermsAfterCycle = s3agFetcher.fetchAll();
        if (!lfPermsAfterCycle.isEmpty() || !s3agPermsAfterCycle.isEmpty()) {
            LOG.error("DIRTY_STARTUP: After one sync cycle, LF has {} permissions and S3AG has {} grants. " +
                    "The sync service replayed a non-empty checkpoint. Clear the checkpoint file before starting.",
                    lfPermsAfterCycle.size(), s3agPermsAfterCycle.size());
            System.exit(1);
        }

        LOG.info("Startup assertions passed — Ranger, LF, and S3AG are clean after one cycle.");
    }

    private static SimulatorConfig buildConfigFromEnv() {
        // Read from environment variables; throw if required vars are missing
        return new SimulatorConfig(
                getRequired("RANGER_ADMIN_URL"),
                getRequired("RANGER_ADMIN_USER"),
                getRequired("RANGER_ADMIN_PASSWORD"),
                getEnv("RANGER_SERVICE_NAME", "lakeformation"),
                getRequired("AWS_REGION"),
                getRequired("AWS_ACCOUNT_ID"),
                getRequired("S3AG_INSTANCE_ARN"),
                java.util.Map.of(
                        "analyst",    getRequired("PRINCIPAL_ANALYST_ARN"),
                        "etl_user",   getRequired("PRINCIPAL_ETL_USER_ARN"),
                        "data_admin", getRequired("PRINCIPAL_DATA_ADMIN_ARN"),
                        "viewer",     getRequired("PRINCIPAL_VIEWER_ARN")),
                List.of(getEnv("GLUE_DATABASES", "finance,marketing,ops").split(",")),
                Long.parseLong(getEnv("RUN_DURATION_MS", "3600000")),  // default 1h
                Integer.parseInt(getEnv("MAX_BATCH_SIZE", "5")),
                Long.parseLong(getEnv("POLICY_REFRESH_INTERVAL_MS", "30000")),
                getEnv("SYNC_SERVICE_STATUS_URL", "http://localhost:18080/status"),
                getEnv("OUTPUT_DIRECTORY", "./simulator-output"),
                getEnv("MUTATION_LOG_PATH", "./simulator-output/mutations.jsonl"),
                getRequired("CHECKPOINT_FILE_PATH")  // e.g. ./checkpoint/sync-checkpoint.json
        );
    }

    private static String getRequired(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + key);
        }
        return value;
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
```

- [ ] **Step 4: Create `SimulatorCleanup.java`** (brief — mirrors startup assertions in reverse)

```java
package com.amazonaws.policyconverters.simulator.driver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SimulatorCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(SimulatorCleanup.class);

    public static void main(String[] args) throws Exception {
        SimulatorConfig config = buildConfigFromEnv();
        try (RangerPolicyClient rangerClient = new RangerPolicyClient(
                config.rangerAdminUrl(), config.rangerAdminUser(), config.rangerAdminPassword())) {
            // Delete all simulator-created policies (those named sim-*)
            List<com.fasterxml.jackson.databind.JsonNode> policies =
                    rangerClient.getPoliciesByService(config.rangerServiceName());
            for (com.fasterxml.jackson.databind.JsonNode policy : policies) {
                if (policy.get("name").asText().startsWith("sim-")) {
                    long id = policy.get("id").asLong();
                    LOG.info("Deleting policy id={} name={}", id, policy.get("name").asText());
                    rangerClient.deletePolicy(id);
                }
            }
            LOG.info("Cleanup complete. Deleted all sim-* policies.");
            LOG.info("Wait for the sync service to process deletions, then verify LF is empty.");
        }
    }

    private static SimulatorConfig buildConfigFromEnv() {
        return SimulatorMain.buildConfigFromEnv();  // reuse — make buildConfigFromEnv package-private
    }
}
```

Note: Make `SimulatorMain.buildConfigFromEnv()` package-private (not private) so `SimulatorCleanup` can call it.

- [ ] **Step 5: Compile the full project**

```bash
mvn compile -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Run all unit tests**

```bash
mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/
git commit -m "feat(simulator): add MutationDriver, SimulatorMain, SimulatorCleanup, HivePolicyGenerator — simulator is smoke-runnable"
```

---

## Task 12: Remaining Policy Generators

**Files:**
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/DataLocationPolicyGenerator.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/EmrfsPolicyGenerator.java`
- Create: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/TagPolicyGenerator.java`
- Modify: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/MutationDriver.java`
- Modify: `simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/SimulatorMain.java`

These generators complete the workload mix. The spec requires 60% Hive/Trino, 15% DataLocation, 15% EMRFS, 10% Tag.

- [ ] **Step 1: Create `DataLocationPolicyGenerator.java`**

Data location policies use the `url` resource type and produce `DATA_LOCATION_ACCESS` grants in LF.

```java
package com.amazonaws.policyconverters.simulator.workload;

import com.amazonaws.policyconverters.simulator.driver.SimulatorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

public class DataLocationPolicyGenerator {
    private final SimulatorConfig config;
    private final Random random;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> SAMPLE_PATHS = List.of(
            "s3://test-bucket/finance/", "s3://test-bucket/marketing/",
            "s3://test-bucket/ops/", "s3://test-bucket/shared/");

    public DataLocationPolicyGenerator(SimulatorConfig config, Random random) {
        this.config = config;
        this.random = random;
    }

    public String generateCreatePayload(long policyId) {
        String path = SAMPLE_PATHS.get(random.nextInt(SAMPLE_PATHS.size()));
        List<String> principalNames = new ArrayList<>(config.principalPool().keySet());
        String principal = principalNames.get(random.nextInt(principalNames.size()));

        ObjectNode policy = mapper.createObjectNode();
        policy.put("service", config.rangerServiceName());
        policy.put("name", "sim-dataloc-" + policyId);
        policy.put("isEnabled", true);

        // url resource maps to DATA_LOCATION_ACCESS in LF
        ObjectNode resources = mapper.createObjectNode();
        ObjectNode urlRes = mapper.createObjectNode();
        urlRes.set("values", mapper.createArrayNode().add(path));
        urlRes.put("isExcludes", false);
        urlRes.put("isRecursive", false);
        resources.set("url", urlRes);
        policy.set("resources", resources);

        ArrayNode accesses = mapper.createArrayNode();
        ObjectNode access = mapper.createObjectNode();
        access.put("type", "read");
        access.put("isAllowed", true);
        accesses.add(access);

        ObjectNode item = mapper.createObjectNode();
        item.set("accesses", accesses);
        item.set("users", mapper.createArrayNode().add(principal));
        item.set("groups", mapper.createArrayNode());
        item.set("roles", mapper.createArrayNode());
        item.set("conditions", mapper.createArrayNode());
        item.put("delegateAdmin", false);
        policy.set("policyItems", mapper.createArrayNode().add(item));
        policy.set("denyPolicyItems", mapper.createArrayNode());

        try {
            return mapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize data location policy", e);
        }
    }
}
```

- [ ] **Step 2: Create `EmrfsPolicyGenerator.java`**

EMRFS policies use the HDFS-style resource type and produce S3 Access Grant entries (not LF permissions).

```java
package com.amazonaws.policyconverters.simulator.workload;

import com.amazonaws.policyconverters.simulator.driver.SimulatorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

public class EmrfsPolicyGenerator {
    private final SimulatorConfig config;
    private final Random random;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> S3_PREFIXES = List.of(
            "s3://test-bucket/emrfs/data/", "s3://test-bucket/emrfs/logs/",
            "s3://test-bucket/emrfs/tmp/");
    private static final List<String> PERMISSIONS = List.of("READ", "WRITE", "READWRITE");

    public EmrfsPolicyGenerator(SimulatorConfig config, Random random) {
        this.config = config;
        this.random = random;
    }

    public String generateCreatePayload(long policyId) {
        String prefix = S3_PREFIXES.get(random.nextInt(S3_PREFIXES.size()));
        List<String> principalNames = new ArrayList<>(config.principalPool().keySet());
        String principal = principalNames.get(random.nextInt(principalNames.size()));
        String permission = PERMISSIONS.get(random.nextInt(PERMISSIONS.size())).toLowerCase();

        ObjectNode policy = mapper.createObjectNode();
        policy.put("service", config.rangerServiceName() + "-emrfs");  // EMRFS service name convention
        policy.put("name", "sim-emrfs-" + policyId);
        policy.put("isEnabled", true);

        // EMRFS/HDFS uses path resource
        ObjectNode resources = mapper.createObjectNode();
        ObjectNode pathRes = mapper.createObjectNode();
        pathRes.set("values", mapper.createArrayNode().add(prefix));
        pathRes.put("isExcludes", false);
        pathRes.put("isRecursive", true);
        resources.set("path", pathRes);
        policy.set("resources", resources);

        ArrayNode accesses = mapper.createArrayNode();
        ObjectNode access = mapper.createObjectNode();
        access.put("type", permission);  // read, write, or execute
        access.put("isAllowed", true);
        accesses.add(access);

        ObjectNode item = mapper.createObjectNode();
        item.set("accesses", accesses);
        item.set("users", mapper.createArrayNode().add(principal));
        item.set("groups", mapper.createArrayNode());
        item.set("roles", mapper.createArrayNode());
        item.set("conditions", mapper.createArrayNode());
        item.put("delegateAdmin", false);
        policy.set("policyItems", mapper.createArrayNode().add(item));
        policy.set("denyPolicyItems", mapper.createArrayNode());

        try {
            return mapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize EMRFS policy", e);
        }
    }
}
```

- [ ] **Step 3: Create `TagPolicyGenerator.java`**

Tag-based policies exercise the gap recording path — they use LF tag expressions that the pipeline cannot fully enforce.

```java
package com.amazonaws.policyconverters.simulator.workload;

import com.amazonaws.policyconverters.simulator.driver.SimulatorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

public class TagPolicyGenerator {
    private final SimulatorConfig config;
    private final Random random;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> TAGS = List.of("pii=true", "env=prod", "team=analytics");

    public TagPolicyGenerator(SimulatorConfig config, Random random) {
        this.config = config;
        this.random = random;
    }

    public String generateCreatePayload(long policyId) {
        String tag = TAGS.get(random.nextInt(TAGS.size()));
        List<String> principalNames = new ArrayList<>(config.principalPool().keySet());
        String principal = principalNames.get(random.nextInt(principalNames.size()));

        ObjectNode policy = mapper.createObjectNode();
        policy.put("service", config.rangerServiceName());
        policy.put("name", "sim-tag-" + policyId);
        policy.put("isEnabled", true);
        policy.put("policyType", 2);  // tag-based policy type

        // Tag-based resource expression
        ObjectNode resources = mapper.createObjectNode();
        ObjectNode tagRes = mapper.createObjectNode();
        tagRes.set("values", mapper.createArrayNode().add(tag));
        tagRes.put("isExcludes", false);
        resources.set("tag", tagRes);
        policy.set("resources", resources);

        ArrayNode accesses = mapper.createArrayNode();
        ObjectNode access = mapper.createObjectNode();
        access.put("type", "select");
        access.put("isAllowed", true);
        accesses.add(access);

        ObjectNode item = mapper.createObjectNode();
        item.set("accesses", accesses);
        item.set("users", mapper.createArrayNode().add(principal));
        item.set("groups", mapper.createArrayNode());
        item.set("roles", mapper.createArrayNode());
        item.set("conditions", mapper.createArrayNode());
        item.put("delegateAdmin", false);
        policy.set("policyItems", mapper.createArrayNode().add(item));
        policy.set("denyPolicyItems", mapper.createArrayNode());

        try {
            return mapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tag policy", e);
        }
    }
}
```

- [ ] **Step 4: Wire all generators into `MutationDriver` and `SimulatorMain`**

In `MutationDriver`:
- Add fields: `DataLocationPolicyGenerator dataLocationGenerator`, `EmrfsPolicyGenerator emrfsGenerator`, `TagPolicyGenerator tagGenerator`
- Add them to the constructor
- In `applyBatch`, expand the `CREATE` case to select generator based on `policyType` probability (60% Hive, 15% DataLocation, 15% EMRFS, 10% Tag):

```java
case CREATE -> {
    // Select generator: 60% Hive, 15% DataLocation, 15% EMRFS, 10% Tag
    int roll = new Random().nextInt(100);
    String json;
    if (roll < 60) {
        json = hiveGenerator.generateCreatePayload(cycleNumber * 1000 + payloads.size(), List.of());
    } else if (roll < 75) {
        json = dataLocationGenerator.generateCreatePayload(cycleNumber * 1000 + payloads.size());
    } else if (roll < 90) {
        json = emrfsGenerator.generateCreatePayload(cycleNumber * 1000 + payloads.size());
    } else {
        json = tagGenerator.generateCreatePayload(cycleNumber * 1000 + payloads.size());
    }
    long id = rangerClient.createPolicy(json);
    activePolicyIds.add(id);
    payloads.add(Map.of("op", "CREATE", "policyId", id, "json", json));
}
```

In `SimulatorMain.main()`, add the generator constructions and pass them to `MutationDriver`:
```java
Random rng = new java.util.Random();
HivePolicyGenerator hiveGenerator = new HivePolicyGenerator(config, rng);
DataLocationPolicyGenerator dataLocationGenerator = new DataLocationPolicyGenerator(config, rng);
EmrfsPolicyGenerator emrfsGenerator = new EmrfsPolicyGenerator(config, rng);
TagPolicyGenerator tagGenerator = new TagPolicyGenerator(config, rng);
```

And update `MutationDriver` constructor call to pass all four generators.

- [ ] **Step 5: Compile**

```bash
mvn compile -pl simulator -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add simulator/src/main/java/com/amazonaws/policyconverters/simulator/workload/ \
        simulator/src/main/java/com/amazonaws/policyconverters/simulator/driver/
git commit -m "feat(simulator): add DataLocationPolicyGenerator, EmrfsPolicyGenerator, TagPolicyGenerator"
```

---

## Task 13: Smoke Test and End-to-End Verification

**Prerequisites:** Dedicated AWS account with IAM roles, Glue catalog, S3AG instance, and Ranger Admin all running.

- [ ] **Step 1: Set environment variables**

```bash
export RANGER_ADMIN_URL=http://localhost:6080
export RANGER_ADMIN_USER=admin
export RANGER_ADMIN_PASSWORD=rangerR0cks!
export RANGER_SERVICE_NAME=lakeformation
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=<your-account-id>
export S3AG_INSTANCE_ARN=<your-s3ag-instance-arn>
export PRINCIPAL_ANALYST_ARN=arn:aws:iam::<account-id>:role/analyst
export PRINCIPAL_ETL_USER_ARN=arn:aws:iam::<account-id>:role/etl_user
export PRINCIPAL_DATA_ADMIN_ARN=arn:aws:iam::<account-id>:role/data_admin
export PRINCIPAL_VIEWER_ARN=arn:aws:iam::<account-id>:role/viewer
export RUN_DURATION_MS=3600000   # 1 hour smoke run
export SYNC_SERVICE_STATUS_URL=http://localhost:18080/status
export OUTPUT_DIRECTORY=./simulator-output
export MUTATION_LOG_PATH=./simulator-output/mutations.jsonl
export CHECKPOINT_FILE_PATH=./checkpoint/sync-checkpoint.json   # must match sync service's checkpointPath
```

- [ ] **Step 2: Start the sync service (in a separate terminal)**

```bash
java -jar target/ranger-lakeformation-plugin-*.jar  # or however the service is run
```

Verify `GET http://localhost:18080/status` returns:
```json
{"lastCompletedCycle":1,"lastCompletedWildcardRefreshCycle":0,"state":"running"}
```

- [ ] **Step 3: Run the 1-hour smoke run**

```bash
mvn exec:java -pl simulator \
  -Dexec.mainClass=com.amazonaws.policyconverters.simulator.driver.SimulatorMain \
  -q 2>&1 | tee simulator-output/smoke-run.log
```

Expected:
- 20+ cycles complete
- Zero `PERSISTENT_VIOLATION` log lines
- At least one `TRANSIENT_VIOLATION` log entry (self-healed), OR zero violations (both acceptable)
- `simulator-output/mutations.jsonl` contains all cycle mutations

- [ ] **Step 4: Fault injection — over-grant test**

While the simulator is running (or after), manually grant an extra LF permission out-of-band:

```bash
aws lakeformation grant-permissions \
  --catalog-id $AWS_ACCOUNT_ID \
  --principal DataLakePrincipalIdentifier=<viewer-role-arn> \
  --resource '{"Table":{"DatabaseName":"finance","Name":"injected_table"}}' \
  --permissions SELECT
```

Within one simulator cycle, expect to see `VIOLATION detected` in the log. After remediation, expect `TRANSIENT_VIOLATION (self-healed)` and a reproduction bundle in `simulator-output/reproduction-bundles/`.

- [ ] **Step 5: Run SimulatorCleanup**

```bash
mvn exec:java -pl simulator \
  -Dexec.mainClass=com.amazonaws.policyconverters.simulator.driver.SimulatorCleanup
```

Verify: all `sim-*` policies deleted from Ranger, LF permissions count returns to zero.

- [ ] **Step 6: Final full build**

```bash
mvn verify -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Final commit**

```bash
git status  # should be clean
```
