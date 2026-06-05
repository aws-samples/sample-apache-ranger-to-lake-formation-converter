# Composite Principal Mapper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `COMPOSITE` principal mapper type that chains an ordered list of mappers (static first, IDC fallback) so Ranger principals are resolved by the first mapper that has a mapping for them.

**Architecture:** Add `COMPOSITE` to `PrincipalMapperType`; extend `PrincipalMappingConfig` with a `delegates` list; implement `CompositePrincipalMapper` that tries delegates in order returning the first non-empty result; refactor `PrincipalMapperFactory` to add explicit `STATIC` and `COMPOSITE` branches (removing the fall-through default); update `ConversionServerMain` to build an `IdentitystoreClient` whenever any delegate in the chain requires one.

**Tech Stack:** Java 17, Jackson (existing `@JsonCreator` pattern), JUnit 5, Mockito, existing `PrincipalMapper` interface.

---

## File map

| File | Action | Purpose |
|---|---|---|
| `config/PrincipalMapperType.java` | Modify | Add `COMPOSITE` value |
| `config/PrincipalMappingConfig.java` | Modify | Add `delegates` field; migrate `@JsonCreator` to new 6-arg constructor |
| `lakeformation/CompositePrincipalMapper.java` | Create | New `PrincipalMapper` impl that chains delegates |
| `lakeformation/PrincipalMapperFactory.java` | Modify | Add explicit `STATIC` branch; add `COMPOSITE` branch with validation; replace fall-through with throw |
| `app/ConversionServerMain.java` | Modify | Build `IdentitystoreClient` when any delegate needs IDC, not just when top-level type is `IDENTITY_CENTER` |
| `conf/server-config.yaml` | Modify | Add composite config example in the `principalMapping` section |
| `test/.../PrincipalMappingConfigTest.java` | Modify | Add tests for `delegates` field deserialization and backward compat |
| `test/.../CompositePrincipalMapperTest.java` | Create | Unit tests for composite resolution logic and metric deduplication |
| `test/.../PrincipalMapperFactoryTest.java` | Modify | Add tests for composite factory branch, validation, and throw on unknown type |
| `test/.../CompositePrincipalMapperIntegrationTest.java` | Create | End-to-end: `RangerToCedarConverter` wired with composite mapper |

---

## Task 1: Add `COMPOSITE` to `PrincipalMapperType`

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/config/PrincipalMapperType.java`

- [ ] **Step 1: Add the enum value**

```java
// src/main/java/com/amazonaws/policyconverters/config/PrincipalMapperType.java
package com.amazonaws.policyconverters.config;

public enum PrincipalMapperType {
    STATIC,
    IDENTITY_CENTER,
    COMPOSITE
}
```

- [ ] **Step 2: Verify existing tests still pass**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -Dtest=PrincipalMapperFactoryTest,PrincipalMappingConfigTest -q
```

Expected: BUILD SUCCESS (no test changes yet — factory still falls through for COMPOSITE, which is safe until Task 3).

**Do not commit here.** The enum addition and the factory fall-through removal must land in the same commit (Task 4 Step 6) to avoid a window where a `COMPOSITE` config would silently produce an empty static mapper.

---

## Task 2: Add `delegates` field to `PrincipalMappingConfig`

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/config/PrincipalMappingConfig.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/config/PrincipalMappingConfigTest.java`

- [ ] **Step 1: Write failing tests first**

Add the following to `PrincipalMappingConfigTest.java` (at the end of the class, before the closing `}`):

```java
// --- delegates field tests ---

@Test
void delegates_defaultsToEmptyList() {
    PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null);
    assertNotNull(config.getDelegates());
    assertTrue(config.getDelegates().isEmpty());
}

@Test
void delegates_jsonRoundTrip() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json = "{"
            + "\"type\": \"COMPOSITE\","
            + "\"delegates\": ["
            + "  {\"type\": \"STATIC\", \"userMappings\": {\"alice\": \"arn:aws:iam::123:role/alice\"}},"
            + "  {\"type\": \"IDENTITY_CENTER\", \"idcConfig\": {"
            + "    \"identityStoreId\": \"d-test\", \"region\": \"us-east-1\","
            + "    \"accountId\": \"123456789012\", \"cacheTtlMinutes\": 60}}"
            + "]}";
    PrincipalMappingConfig config = mapper.readValue(json, PrincipalMappingConfig.class);
    assertEquals(PrincipalMapperType.COMPOSITE, config.getType());
    assertEquals(2, config.getDelegates().size());
    assertEquals(PrincipalMapperType.STATIC, config.getDelegates().get(0).getType());
    assertEquals("arn:aws:iam::123:role/alice",
            config.getDelegates().get(0).getUserMappings().get("alice"));
    assertEquals(PrincipalMapperType.IDENTITY_CENTER, config.getDelegates().get(1).getType());
}

@Test
void delegates_existingConfigsUnaffected() throws Exception {
    // Existing STATIC config without delegates still deserializes unchanged
    ObjectMapper mapper = new ObjectMapper();
    String json = "{\"type\": \"STATIC\", \"userMappings\": {\"bob\": \"arn:aws:iam::123:role/bob\"}}";
    PrincipalMappingConfig config = mapper.readValue(json, PrincipalMappingConfig.class);
    assertEquals(PrincipalMapperType.STATIC, config.getType());
    assertTrue(config.getDelegates().isEmpty());
    assertEquals("arn:aws:iam::123:role/bob", config.getUserMappings().get("bob"));
}
```

- [ ] **Step 2: Run to confirm they fail**

```bash
mvn test -Dtest=PrincipalMappingConfigTest -q 2>&1 | grep -E "ERROR|FAIL|delegates"
```

Expected: compilation errors — `getDelegates()` does not exist yet.

- [ ] **Step 3: Update `PrincipalMappingConfig.java`**

Make these changes:

1. Add import (after existing imports):
```java
import java.util.ArrayList;
import java.util.List;
```

2. Add field (after `idcConfig`):
```java
private final List<PrincipalMappingConfig> delegates;
```

3. Replace the existing 5-arg `@JsonCreator` constructor with a new 6-arg one, and demote the old 5-arg to a plain chain-up:

```java
// Backward-compat 3-arg — unchanged, chains to 5-arg which chains to 6-arg
public PrincipalMappingConfig(
        Map<String, String> userMappings,
        Map<String, String> groupMappings,
        Map<String, String> roleMappings) {
    this(userMappings, groupMappings, roleMappings, null, null);
}

// Backward-compat 5-arg — loses @JsonCreator, chains to new 6-arg
public PrincipalMappingConfig(
        Map<String, String> userMappings,
        Map<String, String> groupMappings,
        Map<String, String> roleMappings,
        PrincipalMapperType type,
        IdentityCenterConfig idcConfig) {
    this(userMappings, groupMappings, roleMappings, type, idcConfig, null);
}

// New canonical @JsonCreator — 6-arg, adds delegates
@JsonCreator
public PrincipalMappingConfig(
        @JsonProperty("userMappings")  Map<String, String> userMappings,
        @JsonProperty("groupMappings") Map<String, String> groupMappings,
        @JsonProperty("roleMappings")  Map<String, String> roleMappings,
        @JsonProperty("type")          PrincipalMapperType type,
        @JsonProperty("idcConfig")     IdentityCenterConfig idcConfig,
        @JsonProperty("delegates")     List<PrincipalMappingConfig> delegates) {
    this.userMappings  = userMappings  != null ? Collections.unmodifiableMap(new HashMap<>(userMappings))  : Collections.<String,String>emptyMap();
    this.groupMappings = groupMappings != null ? Collections.unmodifiableMap(new HashMap<>(groupMappings)) : Collections.<String,String>emptyMap();
    this.roleMappings  = roleMappings  != null ? Collections.unmodifiableMap(new HashMap<>(roleMappings))  : Collections.<String,String>emptyMap();
    this.type          = (type != null) ? type : PrincipalMapperType.STATIC;
    this.idcConfig     = idcConfig;
    this.delegates     = delegates != null
            ? Collections.unmodifiableList(new ArrayList<>(delegates))
            : Collections.emptyList();
}
```

4. Add accessor (after `getIdcConfig()`):
```java
public List<PrincipalMappingConfig> getDelegates() { return delegates; }
```

5. Update both `equals()` and `hashCode()` to include `delegates`. Find the existing `equals()` (it checks all 5 fields) and add `&& Objects.equals(delegates, that.delegates)` to the condition. Update `hashCode()`:
```java
@Override public int hashCode() {
    return Objects.hash(userMappings, groupMappings, roleMappings, type, idcConfig, delegates);
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -Dtest=PrincipalMappingConfigTest -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/config/PrincipalMappingConfig.java \
        src/test/java/com/amazonaws/policyconverters/config/PrincipalMappingConfigTest.java
git commit -m "feat: add delegates field to PrincipalMappingConfig for COMPOSITE mapper"
```

---

## Task 3: Implement `CompositePrincipalMapper`

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapper.java`
- Create: `src/test/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapperTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapperTest.java`:

```java
package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositePrincipalMapperTest {

    @Mock MetricsEmitter metricsEmitter;

    // --- Resolution order ---

    @Test
    void firstDelegateHit_returnsImmediately_doesNotConsultSecond() {
        PrincipalMapper first  = alwaysReturns("arn:first");
        PrincipalMapper second = mock(PrincipalMapper.class);
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(first, second), null);

        assertEquals(Optional.of("arn:first"), composite.resolveUser("alice"));
        verifyNoInteractions(second);
    }

    @Test
    void firstDelegateMiss_fallsThroughToSecond() {
        PrincipalMapper first  = alwaysMisses();
        PrincipalMapper second = alwaysReturns("arn:second");
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(first, second), null);

        assertEquals(Optional.of("arn:second"), composite.resolveUser("alice"));
    }

    @Test
    void allDelegatesMiss_returnsEmpty() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), null);

        assertEquals(Optional.empty(), composite.resolveUser("alice"));
    }

    // --- Metric deduplication ---

    @Test
    void allMiss_recordsUnmappedMetricExactlyOnce_forUser() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), metricsEmitter);

        composite.resolveUser("alice");

        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("user");
        verifyNoMoreInteractions(metricsEmitter);
    }

    @Test
    void allMiss_recordsUnmappedMetricExactlyOnce_forGroup() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), metricsEmitter);

        composite.resolveGroup("admins");

        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("group");
        verifyNoMoreInteractions(metricsEmitter);
    }

    @Test
    void allMiss_recordsUnmappedMetricExactlyOnce_forRole() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses(), alwaysMisses()), metricsEmitter);

        composite.resolveRole("etl_role");

        verify(metricsEmitter, times(1)).recordUnmappedPrincipal("role");
        verifyNoMoreInteractions(metricsEmitter);
    }

    @Test
    void firstDelegateHit_noMetricEmitted() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysReturns("arn:x")), metricsEmitter);

        composite.resolveUser("alice");

        verifyNoInteractions(metricsEmitter);
    }

    @Test
    void firstMissSecondHit_noMetricEmitted() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(
                        List.of(alwaysMisses(), alwaysReturns("arn:x")), metricsEmitter);

        composite.resolveUser("alice");

        verifyNoInteractions(metricsEmitter);
    }

    @Test
    void nullMetricsEmitter_allMiss_doesNotThrow() {
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(alwaysMisses()), null);

        assertDoesNotThrow(() -> composite.resolveUser("alice"));
        assertEquals(Optional.empty(), composite.resolveUser("alice"));
    }

    // --- Delegate methods all forwarded correctly ---

    @Test
    void resolveGroup_usesGroupDelegation() {
        PrincipalMapper first  = alwaysMisses();
        PrincipalMapper second = alwaysReturnsGroup("arn:group");
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(first, second), null);

        assertEquals(Optional.of("arn:group"), composite.resolveGroup("admins"));
    }

    @Test
    void resolveRole_usesRoleDelegation() {
        PrincipalMapper delegate = alwaysReturnsRole("arn:role");
        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(delegate), null);

        assertEquals(Optional.of("arn:role"), composite.resolveRole("etl_role"));
    }

    // --- Helpers ---

    private static PrincipalMapper alwaysReturns(String arn) {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.of(arn));
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveRole(any())).thenReturn(Optional.empty());
        return m;
    }

    private static PrincipalMapper alwaysReturnsGroup(String arn) {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.of(arn));
        lenient().when(m.resolveRole(any())).thenReturn(Optional.empty());
        return m;
    }

    private static PrincipalMapper alwaysReturnsRole(String arn) {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveRole(any())).thenReturn(Optional.of(arn));
        return m;
    }

    private static PrincipalMapper alwaysMisses() {
        PrincipalMapper m = mock(PrincipalMapper.class);
        lenient().when(m.resolveUser(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveGroup(any())).thenReturn(Optional.empty());
        lenient().when(m.resolveRole(any())).thenReturn(Optional.empty());
        return m;
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -Dtest=CompositePrincipalMapperTest -q 2>&1 | grep -E "ERROR|cannot find"
```

Expected: compilation error — `CompositePrincipalMapper` does not exist.

- [ ] **Step 3: Implement `CompositePrincipalMapper`**

Create `src/main/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapper.java`:

```java
package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Chains multiple {@link PrincipalMapper} delegates in order, returning the first
 * non-empty resolution. Only emits the unmapped-principal metric after all delegates
 * are exhausted. Delegates must be constructed with a null MetricsEmitter to avoid
 * double-counting intermediate misses — this is enforced by PrincipalMapperFactory.
 */
public class CompositePrincipalMapper implements PrincipalMapper {

    private static final Logger LOG = LoggerFactory.getLogger(CompositePrincipalMapper.class);

    private final List<PrincipalMapper> delegates;
    private final MetricsEmitter metricsEmitter;  // nullable

    public CompositePrincipalMapper(List<PrincipalMapper> delegates, MetricsEmitter metricsEmitter) {
        this.delegates = Collections.unmodifiableList(delegates);
        this.metricsEmitter = metricsEmitter;
    }

    @Override
    public Optional<String> resolveUser(String rangerUser) {
        return resolve("user", rangerUser, d -> d.resolveUser(rangerUser));
    }

    @Override
    public Optional<String> resolveGroup(String rangerGroup) {
        return resolve("group", rangerGroup, d -> d.resolveGroup(rangerGroup));
    }

    @Override
    public Optional<String> resolveRole(String rangerRole) {
        return resolve("role", rangerRole, d -> d.resolveRole(rangerRole));
    }

    private Optional<String> resolve(String principalType, String name,
                                     java.util.function.Function<PrincipalMapper, Optional<String>> fn) {
        for (PrincipalMapper delegate : delegates) {
            Optional<String> result = fn.apply(delegate);
            if (result.isPresent()) {
                return result;
            }
        }
        LOG.warn("CompositePrincipalMapper: no delegate resolved {} '{}'", principalType, name);
        if (metricsEmitter != null) {
            metricsEmitter.recordUnmappedPrincipal(principalType);
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -Dtest=CompositePrincipalMapperTest -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapper.java \
        src/test/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapperTest.java
git commit -m "feat: implement CompositePrincipalMapper with ordered delegate chain"
```

---

## Task 4: Refactor `PrincipalMapperFactory` — explicit branches + COMPOSITE support

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/lakeformation/PrincipalMapperFactory.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/lakeformation/PrincipalMapperFactoryTest.java`

- [ ] **Step 1: Write failing tests**

Add to the end of `PrincipalMapperFactoryTest.java` (before closing `}`):

```java
import com.amazonaws.policyconverters.config.IdentityCenterConfig;
// (already imported above)

// --- COMPOSITE type ---

@Test
void create_compositeType_buildsChainInOrder() {
    IdentitystoreClient idcClient = mock(IdentitystoreClient.class);

    PrincipalMappingConfig staticDelegate = new PrincipalMappingConfig(
            Map.of("alice", "arn:aws:iam::123:role/alice"),
            Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.STATIC, null);

    PrincipalMappingConfig idcDelegate = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());

    PrincipalMappingConfig composite = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.COMPOSITE, null,
            List.of(staticDelegate, idcDelegate));

    PrincipalMapper result = PrincipalMapperFactory.create(composite, idcClient, null);
    assertInstanceOf(CompositePrincipalMapper.class, result);
}

@Test
void create_compositeType_emptyDelegates_throwsIllegalArgument() {
    PrincipalMappingConfig composite = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.COMPOSITE, null, Collections.emptyList());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> PrincipalMapperFactory.create(composite, null, null));
    assertTrue(ex.getMessage().contains("delegates"));
}

@Test
void create_compositeType_nestedCompositeDelegate_throwsIllegalArgument() {
    PrincipalMappingConfig innerComposite = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.COMPOSITE, null,
            List.of(new PrincipalMappingConfig(null, null, null)));

    PrincipalMappingConfig outer = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.COMPOSITE, null, List.of(innerComposite));

    assertThrows(IllegalArgumentException.class,
            () -> PrincipalMapperFactory.create(outer, null, null));
}

@Test
void create_compositeType_multipleIdcDelegates_throwsIllegalArgument() {
    IdentitystoreClient idcClient = mock(IdentitystoreClient.class);

    PrincipalMappingConfig idc1 = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());
    PrincipalMappingConfig idc2 = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.IDENTITY_CENTER, validIdcConfig());

    PrincipalMappingConfig composite = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.COMPOSITE, null, List.of(idc1, idc2));

    assertThrows(IllegalArgumentException.class,
            () -> PrincipalMapperFactory.create(composite, idcClient, null));
}

@Test
void create_staticType_hasExplicitBranch_returnsStaticMapper() {
    // Verifies the explicit STATIC branch works after the fall-through is removed.
    PrincipalMappingConfig config = new PrincipalMappingConfig(null, null, null,
            PrincipalMapperType.STATIC, null);
    PrincipalMapper result = PrincipalMapperFactory.create(config, null, null);
    assertInstanceOf(StaticPrincipalMapper.class, result);
}

@Test
void create_compositeType_idcDelegateWithNullIdcConfig_throwsIllegalArgument() {
    PrincipalMappingConfig idcNoConfig = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.IDENTITY_CENTER, null);
    PrincipalMappingConfig composite = new PrincipalMappingConfig(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
            PrincipalMapperType.COMPOSITE, null, List.of(idcNoConfig));
    assertThrows(IllegalArgumentException.class,
            () -> PrincipalMapperFactory.create(composite, mock(IdentitystoreClient.class), null));
}
```

Also add these imports at the top of `PrincipalMapperFactoryTest.java` (if not already present):
```java
import java.util.Collections;
import java.util.List;
import java.util.Map;
```

- [ ] **Step 2: Run to confirm new tests fail**

```bash
mvn test -Dtest=PrincipalMapperFactoryTest -q 2>&1 | grep -E "ERROR|FAIL|PASS" | head -20
```

Expected: compilation errors for new tests (missing 6-arg constructor call, missing `CompositePrincipalMapper` import).

- [ ] **Step 3: Rewrite `PrincipalMapperFactory.java`**

```java
package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.IdentityCenterConfig;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;

import java.util.ArrayList;
import java.util.List;

public class PrincipalMapperFactory {

    private PrincipalMapperFactory() { }

    public static PrincipalMapper create(
            PrincipalMappingConfig config,
            IdentitystoreClient identityStoreClient,
            MetricsEmitter metricsEmitter) {

        if (config == null)
            throw new IllegalArgumentException("PrincipalMappingConfig must not be null");

        PrincipalMapperType type = config.getType();

        if (type == PrincipalMapperType.STATIC) {
            return StaticPrincipalMapper.fromConfig(config, metricsEmitter);
        }

        if (type == PrincipalMapperType.IDENTITY_CENTER) {
            return buildIdentityCenter(config, identityStoreClient, metricsEmitter);
        }

        if (type == PrincipalMapperType.COMPOSITE) {
            return buildComposite(config, identityStoreClient, metricsEmitter);
        }

        throw new IllegalArgumentException(
                "Unknown PrincipalMapperType: " + type
                + ". Supported values: STATIC, IDENTITY_CENTER, COMPOSITE");
    }

    private static PrincipalMapper buildIdentityCenter(
            PrincipalMappingConfig config,
            IdentitystoreClient identityStoreClient,
            MetricsEmitter metricsEmitter) {
        IdentityCenterConfig idcConfig = config.getIdcConfig();
        if (idcConfig == null)
            throw new IllegalArgumentException("idcConfig is required when type is IDENTITY_CENTER");
        if (idcConfig.getIdentityStoreId() == null || idcConfig.getIdentityStoreId().isBlank())
            throw new IllegalArgumentException("idcConfig.identityStoreId must not be blank");
        if (idcConfig.getRegion() == null || idcConfig.getRegion().isBlank())
            throw new IllegalArgumentException("idcConfig.region must not be blank");
        if (idcConfig.getAccountId() == null || idcConfig.getAccountId().isBlank())
            throw new IllegalArgumentException("idcConfig.accountId must not be blank");
        if (identityStoreClient == null)
            throw new IllegalArgumentException(
                    "identityStoreClient must not be null when type is IDENTITY_CENTER");
        return new IdentityCenterPrincipalMapper(idcConfig, identityStoreClient, metricsEmitter);
    }

    private static PrincipalMapper buildComposite(
            PrincipalMappingConfig config,
            IdentitystoreClient identityStoreClient,
            MetricsEmitter metricsEmitter) {
        List<PrincipalMappingConfig> delegateConfigs = config.getDelegates();
        if (delegateConfigs == null || delegateConfigs.isEmpty())
            throw new IllegalArgumentException(
                    "COMPOSITE mapper requires at least one entry in 'delegates'");

        long idcCount = delegateConfigs.stream()
                .filter(d -> d.getType() == PrincipalMapperType.IDENTITY_CENTER)
                .count();
        if (idcCount > 1)
            throw new IllegalArgumentException(
                    "COMPOSITE mapper may contain at most one IDENTITY_CENTER delegate; found " + idcCount);

        for (PrincipalMappingConfig d : delegateConfigs) {
            if (d.getType() == PrincipalMapperType.COMPOSITE)
                throw new IllegalArgumentException(
                        "COMPOSITE mapper delegates must not themselves be COMPOSITE (no nesting)");
        }

        // Build delegate instances. Pass null MetricsEmitter so delegates never emit
        // intermediate-miss metrics — CompositePrincipalMapper is the single emitter.
        List<PrincipalMapper> delegates = new ArrayList<>();
        for (PrincipalMappingConfig delegateConfig : delegateConfigs) {
            delegates.add(create(delegateConfig, identityStoreClient, null));
        }
        return new CompositePrincipalMapper(delegates, metricsEmitter);
    }
}
```

- [ ] **Step 4: Run all factory tests**

```bash
mvn test -Dtest=PrincipalMapperFactoryTest -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Run full test suite to catch regressions**

```bash
mvn test -q
```

Expected: BUILD SUCCESS, all existing tests still pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/config/PrincipalMapperType.java \
        src/main/java/com/amazonaws/policyconverters/lakeformation/PrincipalMapperFactory.java \
        src/test/java/com/amazonaws/policyconverters/lakeformation/PrincipalMapperFactoryTest.java
git commit -m "feat: add COMPOSITE PrincipalMapperType; refactor factory — explicit STATIC branch, COMPOSITE support, remove fall-through"
```

---

## Task 5: Update `ConversionServerMain` to build `IdentitystoreClient` for composite configs

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java`

The current code (lines 210–217) builds an `IdentitystoreClient` only when `type == IDENTITY_CENTER`. With composite configs, the top-level type is `COMPOSITE` but a delegate may be `IDENTITY_CENTER` — the client must be built for that case too.

- [ ] **Step 1: Update the `IdentitystoreClient` construction block**

Find the block (around line 207) that reads:
```java
if (principalMappingConfig != null
        && principalMappingConfig.getType() == PrincipalMapperType.IDENTITY_CENTER) {
    identityStoreClient = IdentitystoreClient.builder()
            .region(Region.of(principalMappingConfig.getIdcConfig().getRegion()))
            .credentialsProvider(credentialsProvider)
            .build();
} else {
    identityStoreClient = null;
}
```

Replace with:

```java
if (needsIdentityStoreClient(principalMappingConfig)) {
    String idcRegion = resolveIdcRegion(principalMappingConfig);
    identityStoreClient = IdentitystoreClient.builder()
            .region(Region.of(idcRegion))
            .credentialsProvider(credentialsProvider)
            .build();
} else {
    identityStoreClient = null;
}
```

Add these two private static helpers anywhere in the class (e.g., near the bottom with the other helpers):

```java
/**
 * Returns true if the config requires an IdentitystoreClient — either because
 * the top-level type is IDENTITY_CENTER, or because the config is COMPOSITE with
 * at least one IDENTITY_CENTER delegate.
 */
private static boolean needsIdentityStoreClient(PrincipalMappingConfig config) {
    if (config == null) return false;
    if (config.getType() == PrincipalMapperType.IDENTITY_CENTER) return true;
    if (config.getType() == PrincipalMapperType.COMPOSITE) {
        return config.getDelegates().stream()
                .anyMatch(d -> d.getType() == PrincipalMapperType.IDENTITY_CENTER);
    }
    return false;
}

/**
 * Returns the IDC region to use when building the IdentitystoreClient.
 * For COMPOSITE configs, uses the region from the first IDENTITY_CENTER delegate.
 */
private static String resolveIdcRegion(PrincipalMappingConfig config) {
    if (config.getType() == PrincipalMapperType.IDENTITY_CENTER) {
        return config.getIdcConfig().getRegion();
    }
    // COMPOSITE — find the first IDC delegate
    return config.getDelegates().stream()
            .filter(d -> d.getType() == PrincipalMapperType.IDENTITY_CENTER)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No IDENTITY_CENTER delegate found"))
            .getIdcConfig()
            .getRegion();
}
```

Also ensure the `identityStoreClient.close()` in the shutdown hook remains — it is already null-guarded (`if (identityStoreClient != null)`), so no change needed there.

- [ ] **Step 2: Run the full test suite**

```bash
mvn test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java
git commit -m "feat: build IdentitystoreClient for COMPOSITE configs containing IDENTITY_CENTER delegate"
```

---

## Task 6: Integration test — `CompositePrincipalMapper` end-to-end with `RangerToCedarConverter`

**Files:**
- Create: `src/test/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapperIntegrationTest.java`

This test wires a real `RangerToCedarConverter` with a composite mapper (static first, then a mocked IDC delegate) and verifies that the correct ARN reaches the Cedar output.

- [ ] **Step 1: Write the integration test**

```java
package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import com.amazonaws.policyconverters.ranger.PassthroughCatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that CompositePrincipalMapper integrates correctly with RangerToCedarConverter:
 * - A principal in the static map resolves to the static ARN.
 * - A principal not in the static map resolves via the IDC delegate.
 * - A principal in neither map produces a gap (no Cedar statement).
 */
class CompositePrincipalMapperIntegrationTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String ALICE_STATIC_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/alice-static";
    private static final String BOB_IDC_ARN = "arn:aws:identitystore::" + ACCOUNT_ID + ":user/bob-idc-id";

    @Test
    void staticHit_producesStaticArn() throws Exception {
        RangerToCedarConverter converter = buildConverter();
        RangerPolicy policy = singleUserPolicy("alice", "select", "db1", "t1");

        CedarPolicySet policySet = converter.convert(List.of(policy));
        String cedar = policySet.toCedarString();

        assertTrue(cedar.contains(ALICE_STATIC_ARN),
                "alice is in the static map — Cedar must use the static ARN");
    }

    @Test
    void idcFallback_producesIdcArn() throws Exception {
        RangerToCedarConverter converter = buildConverter();
        RangerPolicy policy = singleUserPolicy("bob", "select", "db1", "t1");

        CedarPolicySet policySet = converter.convert(List.of(policy));
        String cedar = policySet.toCedarString();

        assertTrue(cedar.contains(BOB_IDC_ARN),
                "bob is not in the static map — Cedar must fall back to the IDC ARN");
    }

    @Test
    void bothMiss_producesNoStatement() throws Exception {
        GapReporter gapReporter = new GapReporter();
        RangerToCedarConverter converter = buildConverter(gapReporter);
        RangerPolicy policy = singleUserPolicy("charlie", "select", "db1", "t1");

        CedarPolicySet policySet = converter.convert(List.of(policy));

        assertEquals(0, policySet.getPermitCount(),
                "charlie is in neither map — no Cedar statement should be produced");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RangerToCedarConverter buildConverter() {
        return buildConverter(new GapReporter());
    }

    private RangerToCedarConverter buildConverter(GapReporter gapReporter) {
        // Static delegate: only alice is mapped
        PrincipalMappingConfig staticConfig = new PrincipalMappingConfig(
                Map.of("alice", ALICE_STATIC_ARN),
                Collections.emptyMap(),
                Collections.emptyMap());
        PrincipalMapper staticMapper = StaticPrincipalMapper.fromConfig(staticConfig, null);

        // IDC delegate: only bob is mapped (simulated with a stub)
        PrincipalMapper idcMapper = new PrincipalMapper() {
            @Override public Optional<String> resolveUser(String name) {
                return "bob".equals(name) ? Optional.of(BOB_IDC_ARN) : Optional.empty();
            }
            @Override public Optional<String> resolveGroup(String name) { return Optional.empty(); }
            @Override public Optional<String> resolveRole(String name) { return Optional.empty(); }
        };

        CompositePrincipalMapper composite =
                new CompositePrincipalMapper(List.of(staticMapper, idcMapper), null);

        AwsContext awsContext = new AwsContext("us-east-1", ACCOUNT_ID, ACCOUNT_ID);
        RangerServiceAdapter adapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        CedarSchemaProvider schema = new CedarSchemaProvider();
        return new RangerToCedarConverter(
                registry, composite, new PassthroughCatalogResolver(), gapReporter, schema);
    }

    private static RangerPolicy singleUserPolicy(String user, String access,
                                                  String database, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1L);
        policy.setName("test-policy");
        policy.setService("lakeformation");
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(database));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList(table));
        resources.put("table", tableRes);
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess a = new RangerPolicyItemAccess();
        a.setType(access);
        a.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(a));
        item.setUsers(Collections.singletonList(user));
        item.setDelegateAdmin(false);
        policy.setPolicyItems(Collections.singletonList(item));
        return policy;
    }
}
```

- [ ] **Step 2: Run to confirm tests fail**

```bash
mvn test -Dtest=CompositePrincipalMapperIntegrationTest -q 2>&1 | grep -E "ERROR|FAIL|PASS"
```

Expected: BUILD FAILURE — `CompositePrincipalMapper` class not found (compile error). This is the expected red state before Task 3's implementation is in place. If `CompositePrincipalMapper` already exists from Task 3, the tests should pass immediately.

- [ ] **Step 3: Run once test compiles**

```bash
mvn test -Dtest=CompositePrincipalMapperIntegrationTest -q
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 4: Run full test suite**

```bash
mvn test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/lakeformation/CompositePrincipalMapperIntegrationTest.java
git commit -m "test: add CompositePrincipalMapper integration test with RangerToCedarConverter"
```

---

## Task 7: Document the composite config in `server-config.yaml`

**Files:**
- Modify: `conf/server-config.yaml`

- [ ] **Step 1: Add composite example to the `principalMapping` section**

Find the existing `principalMapping:` section (look for the `# Map Ranger users to IAM user/role ARNs` comment) and add the following block directly after the existing `roleMappings` example:

```yaml
  # --- Composite mapper (static overrides + IDC fallback) ---
  # Use type: COMPOSITE to chain mappers. The first delegate to resolve a name wins.
  # Static mappings are checked first (fast, no I/O); IDC is the fallback.
  # Roles are only resolved by STATIC delegates — Identity Center has no role concept.
  #
  # type: COMPOSITE
  # delegates:
  #   - type: STATIC
  #     userMappings:
  #       "service-account": "arn:aws:iam::123456789012:role/ServiceAccountRole"
  #     groupMappings: {}
  #     roleMappings:
  #       "etl_role": "arn:aws:iam::123456789012:role/EtlRole"
  #   - type: IDENTITY_CENTER
  #     idcConfig:
  #       identityStoreId: "d-1234567890"
  #       region: "us-east-1"
  #       accountId: "123456789012"
  #       cacheTtlMinutes: 60
```

- [ ] **Step 2: Commit**

```bash
git add conf/server-config.yaml
git commit -m "docs: add COMPOSITE principal mapper example to server-config.yaml"
```

---

## Verification

Run the complete test suite one final time:

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -q
```

Expected: BUILD SUCCESS. New test count should be approximately 1006 + (13 new tests from Tasks 2–6) = ~1019.
