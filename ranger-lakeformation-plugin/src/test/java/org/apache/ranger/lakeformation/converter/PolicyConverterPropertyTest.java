package org.apache.ranger.lakeformation.converter;

import net.jqwik.api.*;
import org.apache.ranger.lakeformation.catalog.CatalogResolver;
import org.apache.ranger.lakeformation.mapper.PrincipalMapper;
import org.apache.ranger.lakeformation.model.*;
import org.apache.ranger.lakeformation.model.GapEntry.GapType;
import org.apache.ranger.lakeformation.model.LFPermissionOperation.OperationType;
import org.apache.ranger.lakeformation.reporter.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.*;
import org.apache.ranger.plugin.model.RangerValiditySchedule;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for PolicyConverter.
 * Uses jqwik to verify conversion correctness across randomized inputs.
 */
class PolicyConverterPropertyTest {

    private static final String CATALOG_ID = "123456789012";

    // Known access type -> expected LF permissions mapping
    private static final Map<String, Set<LFPermission>> ACCESS_TYPE_MAP;
    static {
        Map<String, Set<LFPermission>> m = new HashMap<>();
        m.put("select", EnumSet.of(LFPermission.SELECT));
        m.put("update", EnumSet.of(LFPermission.INSERT));
        m.put("create", EnumSet.of(LFPermission.CREATE_TABLE));
        m.put("drop", EnumSet.of(LFPermission.DROP));
        m.put("alter", EnumSet.of(LFPermission.ALTER));
        m.put("read", EnumSet.of(LFPermission.SELECT));
        m.put("write", EnumSet.of(LFPermission.INSERT));
        m.put("all", EnumSet.of(LFPermission.SELECT, LFPermission.INSERT, LFPermission.DELETE,
                LFPermission.ALTER, LFPermission.DROP, LFPermission.DESCRIBE));
        ACCESS_TYPE_MAP = Collections.unmodifiableMap(m);
    }

    private static final List<String> KNOWN_ACCESS_TYPES = Collections.unmodifiableList(
            new ArrayList<>(ACCESS_TYPE_MAP.keySet()));


    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 5: Access type mapping correctness
    // **Validates: Requirements 2.1**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void accessTypeMappingCorrectness(
            @ForAll("knownAccessTypeSets") Set<String> accessTypes
    ) {
        // Build expected LF permissions as the union of all mapped access types
        Set<LFPermission> expectedPermissions = EnumSet.noneOf(LFPermission.class);
        for (String at : accessTypes) {
            expectedPermissions.addAll(ACCESS_TYPE_MAP.get(at));
        }

        RangerPolicy policy = buildSimplePolicy(1L, "test_db", "test_table", accessTypes, "alice");

        PrincipalMapper mapper = buildMapper("alice", "arn:aws:iam::123:user/alice");
        CatalogResolver resolver = mockPassthroughResolver();
        GapReporter reporter = new GapReporter();

        PolicyConverter converter = new PolicyConverter(CATALOG_ID);
        List<LFPermissionOperation> ops = converter.convert(policy, mapper, resolver, reporter);

        assertFalse(ops.isEmpty(), "Should produce at least one operation for valid policy");

        // All operations should have exactly the expected permissions
        for (LFPermissionOperation op : ops) {
            assertEquals(expectedPermissions, op.getPermissions(),
                    "Permissions should match the mapped access types: " + accessTypes);
        }
    }

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 6: Row filter conversion preserves filter expressions
    // **Validates: Requirements 2.2**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void rowFilterConversionPreservesFilterExpressions(
            @ForAll("filterExpressions") String filterExpr
    ) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(2L);
        policy.setName("row_filter_policy");
        policy.setService("hive");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("analytics"));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList("events"));
        resources.put("table", tableRes);
        policy.setResources(resources);

        // Create row filter policy item
        RangerRowFilterPolicyItem filterItem = new RangerRowFilterPolicyItem();
        RangerPolicyItemRowFilterInfo rowFilterInfo = new RangerPolicyItemRowFilterInfo();
        rowFilterInfo.setFilterExpr(filterExpr);
        filterItem.setRowFilterInfo(rowFilterInfo);
        filterItem.setUsers(Collections.singletonList("bob"));

        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        filterItem.setAccesses(Collections.singletonList(access));

        policy.setRowFilterPolicyItems(Collections.singletonList(filterItem));

        PrincipalMapper mapper = buildMapper("bob", "arn:aws:iam::123:user/bob");
        CatalogResolver resolver = mockPassthroughResolver();
        GapReporter reporter = new GapReporter();

        PolicyConverter converter = new PolicyConverter(CATALOG_ID);
        List<LFPermissionOperation> ops = converter.convert(policy, mapper, resolver, reporter);

        assertFalse(ops.isEmpty(), "Should produce operations for row filter policy");

        // Every operation from the row filter should carry the filter expression
        for (LFPermissionOperation op : ops) {
            assertNotNull(op.getResource().getRowFilterExpression(),
                    "Row filter operation should have a filter expression");
            assertEquals(filterExpr, op.getResource().getRowFilterExpression(),
                    "Filter expression should be preserved exactly");
        }
    }

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 7: Principal mapping resolution
    // **Validates: Requirements 2.3, 6.1, 6.2**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void principalMappingResolution(
            @ForAll("principalTypes") String principalType,
            @ForAll("principalNames") String principalName,
            @ForAll("iamArns") String expectedArn
    ) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(3L);
        policy.setName("principal_test_policy");
        policy.setService("hive");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("mydb"));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList("mytable"));
        resources.put("table", tableRes);
        policy.setResources(resources);

        // Create policy item with the principal
        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(access));

        if ("user".equals(principalType)) {
            item.setUsers(Collections.singletonList(principalName));
        } else if ("group".equals(principalType)) {
            item.setGroups(Collections.singletonList(principalName));
        } else {
            item.setRoles(Collections.singletonList(principalName));
        }
        policy.setPolicyItems(Collections.singletonList(item));

        // Build mapper with the specific mapping
        Map<String, String> userMap = new HashMap<>();
        Map<String, String> groupMap = new HashMap<>();
        Map<String, String> roleMap = new HashMap<>();
        if ("user".equals(principalType)) {
            userMap.put(principalName, expectedArn);
        } else if ("group".equals(principalType)) {
            groupMap.put(principalName, expectedArn);
        } else {
            roleMap.put(principalName, expectedArn);
        }
        PrincipalMapper mapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, groupMap, roleMap));

        CatalogResolver resolver = mockPassthroughResolver();
        GapReporter reporter = new GapReporter();

        PolicyConverter converter = new PolicyConverter(CATALOG_ID);
        List<LFPermissionOperation> ops = converter.convert(policy, mapper, resolver, reporter);

        assertFalse(ops.isEmpty(), "Should produce operations for mapped principal");

        for (LFPermissionOperation op : ops) {
            assertEquals(expectedArn, op.getPrincipalArn(),
                    "Principal ARN should match the configured mapping for " + principalType + ":" + principalName);
        }
    }


    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 8: Unsupported feature detection and gap reporting
    // **Validates: Requirements 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void unsupportedFeatureDetectionAndGapReporting(
            @ForAll("unsupportedFeatureSets") Set<String> features
    ) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(4L);
        policy.setName("gap_test_policy");
        policy.setService("hive");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("gapdb"));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList("gaptable"));
        resources.put("table", tableRes);
        policy.setResources(resources);

        // Always add a valid allow item so the policy is not entirely empty
        RangerPolicyItem allowItem = new RangerPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        allowItem.setAccesses(Collections.singletonList(access));
        allowItem.setUsers(Collections.singletonList("validuser"));
        policy.setPolicyItems(Collections.singletonList(allowItem));

        // Track expected gap types
        Set<GapType> expectedGapTypes = new HashSet<>();

        if (features.contains("datamask")) {
            RangerDataMaskPolicyItem maskItem = new RangerDataMaskPolicyItem();
            maskItem.setUsers(Collections.singletonList("maskuser"));
            policy.setDataMaskPolicyItems(Collections.singletonList(maskItem));
            expectedGapTypes.add(GapType.DATA_MASKING);
        }

        if (features.contains("deny")) {
            RangerPolicyItem denyItem = new RangerPolicyItem();
            denyItem.setUsers(Collections.singletonList("denyuser"));
            policy.setDenyPolicyItems(Collections.singletonList(denyItem));
            expectedGapTypes.add(GapType.DENY_POLICY);
        }

        if (features.contains("deny_exception")) {
            RangerPolicyItem denyExItem = new RangerPolicyItem();
            denyExItem.setUsers(Collections.singletonList("denyexuser"));
            policy.setDenyExceptions(Collections.singletonList(denyExItem));
            expectedGapTypes.add(GapType.DENY_EXCEPTION);
        }

        if (features.contains("validity_schedule")) {
            RangerValiditySchedule schedule = new RangerValiditySchedule();
            policy.setValiditySchedules(Collections.singletonList(schedule));
            expectedGapTypes.add(GapType.VALIDITY_SCHEDULE);
        }

        if (features.contains("condition")) {
            RangerPolicyItemCondition condition = new RangerPolicyItemCondition();
            condition.setType("ip-range");
            condition.setValues(Collections.singletonList("10.0.0.0/8"));
            policy.setConditions(Collections.singletonList(condition));
            expectedGapTypes.add(GapType.CUSTOM_CONDITION);
        }

        if (features.contains("security_zone")) {
            policy.setZoneName("finance_zone");
            expectedGapTypes.add(GapType.SECURITY_ZONE);
        }

        PrincipalMapper mapper = buildMapper("validuser", "arn:aws:iam::123:user/validuser");
        CatalogResolver resolver = mockPassthroughResolver();
        GapReporter reporter = new GapReporter();

        PolicyConverter converter = new PolicyConverter(CATALOG_ID);
        List<LFPermissionOperation> ops = converter.convert(policy, mapper, resolver, reporter);

        // Supported portions should still produce operations
        assertFalse(ops.isEmpty(),
                "Should produce operations for the supported allow item even with unsupported features");

        // Verify gap entries match expected unsupported features
        GapReport report = reporter.getReport();
        Set<GapType> actualGapTypes = new HashSet<>();
        for (GapEntry entry : report.getEntries()) {
            actualGapTypes.add(entry.getGapType());
            assertEquals("4", entry.getPolicyId(), "Gap entry should reference the correct policy ID");
        }

        assertTrue(actualGapTypes.containsAll(expectedGapTypes),
                "All expected gap types should be recorded. Expected: " + expectedGapTypes
                        + ", Actual: " + actualGapTypes);
    }

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 9: Conversion determinism
    // **Validates: Requirements 2.6**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void conversionDeterminism(
            @ForAll("knownAccessTypeSets") Set<String> accessTypes,
            @ForAll("principalNames") String principalName
    ) {
        RangerPolicy policy = buildSimplePolicy(5L, "detdb", "dettable", accessTypes, principalName);

        String arn = "arn:aws:iam::123:user/" + principalName;
        PrincipalMapper mapper = buildMapper(principalName, arn);
        CatalogResolver resolver = mockPassthroughResolver();

        PolicyConverter converter = new PolicyConverter(CATALOG_ID);

        // Convert twice with fresh GapReporters
        GapReporter reporter1 = new GapReporter();
        List<LFPermissionOperation> ops1 = converter.convert(policy, mapper, resolver, reporter1);

        GapReporter reporter2 = new GapReporter();
        List<LFPermissionOperation> ops2 = converter.convert(policy, mapper, resolver, reporter2);

        assertEquals(ops1, ops2, "Converting the same policy twice should produce identical results");
    }

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 10: Wildcard expansion completeness
    // **Validates: Requirements 2.7**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void wildcardExpansionCompleteness(
            @ForAll("wildcardDatabaseSets") List<String> matchingDatabases
    ) {
        Assume.that(!matchingDatabases.isEmpty());

        RangerPolicy policy = new RangerPolicy();
        policy.setId(6L);
        policy.setName("wildcard_policy");
        policy.setService("hive");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("db_*")); // wildcard pattern
        resources.put("database", dbRes);
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(access));
        item.setUsers(Collections.singletonList("wcuser"));
        policy.setPolicyItems(Collections.singletonList(item));

        PrincipalMapper mapper = buildMapper("wcuser", "arn:aws:iam::123:user/wcuser");

        // Mock CatalogResolver to return the generated matching databases
        CatalogResolver resolver = mock(CatalogResolver.class);
        when(resolver.expandDatabases("db_*")).thenReturn(matchingDatabases);

        GapReporter reporter = new GapReporter();
        PolicyConverter converter = new PolicyConverter(CATALOG_ID);
        List<LFPermissionOperation> ops = converter.convert(policy, mapper, resolver, reporter);

        // Should produce exactly one operation per matching database (database-level, no tables)
        assertEquals(matchingDatabases.size(), ops.size(),
                "Should produce one LF operation per matching database from wildcard expansion");

        Set<String> opDatabases = new HashSet<>();
        for (LFPermissionOperation op : ops) {
            opDatabases.add(op.getResource().getDatabaseName());
        }
        assertEquals(new HashSet<>(matchingDatabases), opDatabases,
                "Operations should cover exactly the matching databases");
    }


    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 17: Malformed policy resilience
    // **Validates: Requirements 8.1**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void malformedPolicyResilience(
            @ForAll("batchesWithMalformed") BatchWithMalformed batch
    ) {
        PrincipalMapper mapper = buildMapper("batchuser", "arn:aws:iam::123:user/batchuser");
        CatalogResolver resolver = mockPassthroughResolver();
        GapReporter reporter = new GapReporter();

        PolicyConverter converter = new PolicyConverter(CATALOG_ID);
        ConversionResult result = converter.convertBatch(batch.policies, mapper, resolver, reporter);

        // Total processed (success + skipped) should equal total policies in batch
        assertEquals(batch.validCount + batch.malformedCount,
                result.getSuccessCount() + result.getSkippedCount(),
                "Total processed should equal total policies in batch");

        // Malformed policies (null resources) are handled gracefully — they don't crash the batch.
        // They return empty ops but still count as processed.
        // Valid policies should produce operations
        if (batch.validCount > 0) {
            assertFalse(result.getOperations().isEmpty(),
                    "Valid policies in the batch should produce operations");
        }

        // All operations should come from valid policies only
        Set<String> validPolicyIds = new HashSet<>();
        for (RangerPolicy p : batch.policies) {
            if (p.getResources() != null && !p.getResources().isEmpty()) {
                validPolicyIds.add(String.valueOf(p.getId()));
            }
        }
        for (LFPermissionOperation op : result.getOperations()) {
            assertTrue(validPolicyIds.contains(op.getSourcePolicyId()),
                    "Operations should only come from valid policies, got policyId: " + op.getSourcePolicyId());
        }
    }

    // -----------------------------------------------------------------------
    // Arbitraries (generators)
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<Set<String>> knownAccessTypeSets() {
        return Arbitraries.of(KNOWN_ACCESS_TYPES)
                .set()
                .ofMinSize(1)
                .ofMaxSize(KNOWN_ACCESS_TYPES.size());
    }

    @Provide
    Arbitrary<String> filterExpressions() {
        return Arbitraries.of(
                "status = 'active'",
                "region IN ('us-east-1', 'eu-west-1')",
                "amount > 100",
                "department = 'engineering' AND level >= 3",
                "year = 2024",
                "country != 'restricted'",
                "age BETWEEN 18 AND 65",
                "name LIKE 'A%'"
        );
    }

    @Provide
    Arbitrary<String> principalTypes() {
        return Arbitraries.of("user", "group", "role");
    }

    @Provide
    Arbitrary<String> principalNames() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15);
    }

    @Provide
    Arbitrary<String> iamArns() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
                .map(name -> "arn:aws:iam::123456789012:user/" + name);
    }

    @Provide
    Arbitrary<Set<String>> unsupportedFeatureSets() {
        return Arbitraries.of("datamask", "deny", "deny_exception",
                        "validity_schedule", "condition", "security_zone")
                .set()
                .ofMinSize(1)
                .ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<String>> wildcardDatabaseSets() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(12)
                .map(s -> "db_" + s.toLowerCase())
                .list()
                .ofMinSize(1)
                .ofMaxSize(5)
                .uniqueElements();
    }

    @Provide
    Arbitrary<BatchWithMalformed> batchesWithMalformed() {
        return Combinators.combine(
                Arbitraries.integers().between(1, 5),  // valid count
                Arbitraries.integers().between(1, 3)   // malformed count
        ).as((validCount, malformedCount) -> {
            List<RangerPolicy> policies = new ArrayList<>();

            // Add valid policies
            for (int i = 0; i < validCount; i++) {
                policies.add(buildSimplePolicy(
                        (long) (100 + i), "batchdb", "batchtable",
                        Collections.singleton("select"), "batchuser"));
            }

            // Add malformed policies (missing resources)
            for (int i = 0; i < malformedCount; i++) {
                RangerPolicy malformed = new RangerPolicy();
                malformed.setId((long) (200 + i));
                malformed.setName("malformed_" + i);
                malformed.setService("hive");
                // No resources set — this makes it malformed
                malformed.setResources(null);
                policies.add(malformed);
            }

            // Shuffle to interleave valid and malformed
            Collections.shuffle(policies);
            return new BatchWithMalformed(policies, validCount, malformedCount);
        });
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Build a simple valid RangerPolicy with the given parameters.
     */
    private static RangerPolicy buildSimplePolicy(Long id, String database, String table,
                                                   Set<String> accessTypes, String userName) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("policy_" + id);
        policy.setService("hive");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(database));
        resources.put("database", dbRes);
        if (table != null) {
            RangerPolicyResource tableRes = new RangerPolicyResource();
            tableRes.setValues(Collections.singletonList(table));
            resources.put("table", tableRes);
        }
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        List<RangerPolicyItemAccess> accesses = new ArrayList<>();
        for (String at : accessTypes) {
            RangerPolicyItemAccess a = new RangerPolicyItemAccess();
            a.setType(at);
            a.setIsAllowed(true);
            accesses.add(a);
        }
        item.setAccesses(accesses);
        item.setUsers(Collections.singletonList(userName));
        policy.setPolicyItems(Collections.singletonList(item));

        return policy;
    }

    /**
     * Build a PrincipalMapper with a single user mapping.
     */
    private static PrincipalMapper buildMapper(String userName, String arn) {
        Map<String, String> userMap = new HashMap<>();
        userMap.put(userName, arn);
        return PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.<String, String>emptyMap(),
                        Collections.<String, String>emptyMap()));
    }

    /**
     * Create a mock CatalogResolver that passes through non-wildcard values.
     * For wildcard patterns, returns empty (no expansion).
     * For non-wildcard database/table names, returns them as-is.
     */
    private static CatalogResolver mockPassthroughResolver() {
        CatalogResolver resolver = mock(CatalogResolver.class);
        // By default, expandDatabases returns the pattern itself for non-wildcards
        when(resolver.expandDatabases(anyString())).thenAnswer(invocation -> {
            String pattern = invocation.getArgument(0);
            if (pattern.contains("*") || pattern.contains("?")) {
                return Collections.emptyList();
            }
            return Collections.singletonList(pattern);
        });
        when(resolver.expandTables(anyString(), anyString())).thenAnswer(invocation -> {
            String pattern = invocation.getArgument(1);
            if (pattern.contains("*") || pattern.contains("?")) {
                return Collections.emptyList();
            }
            return Collections.singletonList(pattern);
        });
        when(resolver.expandColumns(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String pattern = invocation.getArgument(2);
            if (pattern.contains("*") || pattern.contains("?")) {
                return Collections.emptyList();
            }
            return Collections.singletonList(pattern);
        });
        return resolver;
    }

    // -----------------------------------------------------------------------
    // Helper types
    // -----------------------------------------------------------------------

    static class BatchWithMalformed {
        final List<RangerPolicy> policies;
        final int validCount;
        final int malformedCount;

        BatchWithMalformed(List<RangerPolicy> policies, int validCount, int malformedCount) {
            this.policies = policies;
            this.validCount = validCount;
            this.malformedCount = malformedCount;
        }
    }
}
