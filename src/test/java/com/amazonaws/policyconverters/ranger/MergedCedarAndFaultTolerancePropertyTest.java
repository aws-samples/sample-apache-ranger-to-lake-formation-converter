package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import com.amazonaws.policyconverters.reporting.GapReporter;
import net.jqwik.api.*;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.util.ServicePolicies;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Feature: multi-ranger-plugin-support, Property 7 & Property 8

/**
 * Property-based tests for:
 * <ul>
 *   <li>Property 7: Merged Cedar Set Completeness — policies from N service types
 *       all appear in the merged Cedar set with correct @source annotations.</li>
 *   <li>Property 8: Last-Known-Good Fault Tolerance — after a successful fetch,
 *       a subsequent failure returns the last-known-good policies.</li>
 * </ul>
 */
@Tag("multi-ranger-plugin-support")
class MergedCedarAndFaultTolerancePropertyTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");

    // -----------------------------------------------------------------------
    // Property 7: Merged Cedar Set Completeness
    // Generate policies from N service types, verify merged Cedar set contains
    // contributions from all types.
    // **Validates: Requirements 7.2, 7.3**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void mergedCedarSetContainsContributionsFromAllServiceTypes(
            @ForAll("lakeformationPolicies") List<RangerPolicy> lfPolicies,
            @ForAll("hivePolicies") List<RangerPolicy> hivePolicies
    ) {
        // Build converter with both adapters registered
        RangerToCedarConverter converter = buildConverter("lakeformation", "hive");

        // Merge policies from both service types
        List<RangerPolicy> mergedPolicies = new ArrayList<>(lfPolicies);
        mergedPolicies.addAll(hivePolicies);

        CedarPolicySet cedarMerged = converter.convert(mergedPolicies);
        List<String> sourceIds = cedarMerged.getSourcePolicyIds();

        // Verify lakeformation policies appear with "lakeformation:" prefix
        Set<String> lfSourcePrefixes = new HashSet<>();
        Set<String> hiveSourcePrefixes = new HashSet<>();
        for (String sourceId : sourceIds) {
            assertTrue(sourceId.contains(":"),
                    "Source ID should contain service type prefix: " + sourceId);
            String prefix = sourceId.substring(0, sourceId.indexOf(':'));
            if ("lakeformation".equals(prefix)) {
                lfSourcePrefixes.add(sourceId);
            } else if ("hive".equals(prefix)) {
                hiveSourcePrefixes.add(sourceId);
            }
        }

        // Both service types must have contributed policies
        assertFalse(lfSourcePrefixes.isEmpty(),
                "Merged Cedar set should contain lakeformation policies");
        assertFalse(hiveSourcePrefixes.isEmpty(),
                "Merged Cedar set should contain hive policies");

        // Total policies should be sum of both
        int totalStatements = cedarMerged.getPermitCount() + cedarMerged.getForbidCount();
        assertTrue(totalStatements > 0,
                "Merged Cedar set should have at least one statement");
    }

    @Property(tries = 100)
    void eachServiceTypePolicyIsDispatchedToCorrectAdapter(
            @ForAll("lakeformationPolicies") List<RangerPolicy> lfPolicies,
            @ForAll("hivePolicies") List<RangerPolicy> hivePolicies
    ) {
        RangerToCedarConverter converter = buildConverter("lakeformation", "hive");

        // Convert each service type independently
        CedarPolicySet cedarLf = converter.convert(lfPolicies);
        CedarPolicySet cedarHive = converter.convert(hivePolicies);

        // Convert merged
        List<RangerPolicy> merged = new ArrayList<>(lfPolicies);
        merged.addAll(hivePolicies);
        CedarPolicySet cedarMerged = converter.convert(merged);

        // The merged set should have at least as many permits as each individual set
        assertTrue(cedarMerged.getPermitCount() >= cedarLf.getPermitCount(),
                "Merged permits should be >= lakeformation permits");
        assertTrue(cedarMerged.getPermitCount() >= cedarHive.getPermitCount(),
                "Merged permits should be >= hive permits");

        // All source IDs from individual conversions should appear in merged
        List<String> lfIds = cedarLf.getSourcePolicyIds();
        List<String> hiveIds = cedarHive.getSourcePolicyIds();
        List<String> mergedIds = cedarMerged.getSourcePolicyIds();

        for (String id : lfIds) {
            assertTrue(mergedIds.contains(id),
                    "Merged set should contain lakeformation source ID: " + id);
        }
        for (String id : hiveIds) {
            assertTrue(mergedIds.contains(id),
                    "Merged set should contain hive source ID: " + id);
        }
    }

    // -----------------------------------------------------------------------
    // Property 8: Last-Known-Good Fault Tolerance
    // Generate fetch sequences with intermittent failures, verify
    // last-known-good policies are used.
    // **Validates: Requirements 7.5, 7.6**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void lastKnownGoodPoliciesReturnedAfterFetchFailure(
            @ForAll("lakeformationPolicies") List<RangerPolicy> successPolicies
    ) {
        // Create a testable BaseRangerService subclass
        TestableRangerService service = new TestableRangerService("lakeformation", "lf_test");

        // Step 1: Simulate a successful fetch by setting latestPolicies
        ServicePolicies successSp = new ServicePolicies();
        successSp.setPolicies(new ArrayList<>(successPolicies));
        successSp.setPolicyVersion(1L);
        service.setLatestPolicies(successSp);

        // Step 2: Call getLatestPolicies() — this updates lastKnownGoodPolicies
        ServicePolicies fetched = service.getLatestPolicies();
        assertNotNull(fetched, "First fetch should return non-null policies");
        assertEquals(successPolicies.size(), fetched.getPolicies().size(),
                "First fetch should return all policies");

        // Verify lastKnownGoodPolicies is now populated
        List<RangerPolicy> lastGood = service.getLastKnownGoodPolicies();
        assertEquals(successPolicies.size(), lastGood.size(),
                "lastKnownGoodPolicies should match the successful fetch");

        // Step 3: Simulate a failure by setting latestPolicies to null
        service.setLatestPolicies(null);

        // Step 4: Call getLatestPolicies() — returns null (failure)
        ServicePolicies failedFetch = service.getLatestPolicies();
        assertNull(failedFetch, "Failed fetch should return null");

        // Step 5: Verify lastKnownGoodPolicies still returns the previous success
        List<RangerPolicy> fallback = service.getLastKnownGoodPolicies();
        assertEquals(successPolicies.size(), fallback.size(),
                "lastKnownGoodPolicies should still return the previously successful policies");

        // Verify the actual policy content matches
        for (int i = 0; i < successPolicies.size(); i++) {
            assertEquals(successPolicies.get(i).getId(), fallback.get(i).getId(),
                    "Fallback policy ID should match original at index " + i);
        }
    }

    @Property(tries = 100)
    void lastKnownGoodSurvivesMultipleConsecutiveFailures(
            @ForAll("hivePolicies") List<RangerPolicy> successPolicies,
            @ForAll("failureCount") int failureCount
    ) {
        TestableRangerService service = new TestableRangerService("hive", "hive_test");

        // Successful fetch
        ServicePolicies successSp = new ServicePolicies();
        successSp.setPolicies(new ArrayList<>(successPolicies));
        successSp.setPolicyVersion(1L);
        service.setLatestPolicies(successSp);
        service.getLatestPolicies(); // triggers lastKnownGood update

        // Multiple consecutive failures
        service.setLatestPolicies(null);
        for (int i = 0; i < failureCount; i++) {
            ServicePolicies result = service.getLatestPolicies();
            assertNull(result, "Fetch should return null on failure iteration " + i);
        }

        // lastKnownGoodPolicies should still be intact
        List<RangerPolicy> fallback = service.getLastKnownGoodPolicies();
        assertEquals(successPolicies.size(), fallback.size(),
                "lastKnownGoodPolicies should survive " + failureCount + " consecutive failures");
    }

    @Property(tries = 100)
    void lastKnownGoodUpdatesOnSubsequentSuccess(
            @ForAll("lakeformationPolicies") List<RangerPolicy> firstBatch,
            @ForAll("hivePolicies") List<RangerPolicy> secondBatch
    ) {
        TestableRangerService service = new TestableRangerService("lakeformation", "lf_test");

        // First successful fetch
        ServicePolicies sp1 = new ServicePolicies();
        sp1.setPolicies(new ArrayList<>(firstBatch));
        sp1.setPolicyVersion(1L);
        service.setLatestPolicies(sp1);
        service.getLatestPolicies();

        assertEquals(firstBatch.size(), service.getLastKnownGoodPolicies().size());

        // Failure
        service.setLatestPolicies(null);
        service.getLatestPolicies();
        assertEquals(firstBatch.size(), service.getLastKnownGoodPolicies().size(),
                "After failure, lastKnownGood should still be first batch");

        // Second successful fetch with different policies
        ServicePolicies sp2 = new ServicePolicies();
        sp2.setPolicies(new ArrayList<>(secondBatch));
        sp2.setPolicyVersion(2L);
        service.setLatestPolicies(sp2);
        service.getLatestPolicies();

        // lastKnownGood should now reflect the second batch
        assertEquals(secondBatch.size(), service.getLastKnownGoodPolicies().size(),
                "After second success, lastKnownGood should be updated to second batch");
    }

    // -----------------------------------------------------------------------
    // Testable BaseRangerService subclass
    // -----------------------------------------------------------------------

    /**
     * Minimal concrete subclass of BaseRangerService for testing the
     * lastKnownGoodPolicies mechanism without requiring Ranger Admin.
     */
    private static class TestableRangerService extends BaseRangerService {

        TestableRangerService(String serviceType, String serviceInstanceName) {
            super(serviceType, serviceInstanceName);
        }

        @Override
        public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
            if ("hive".equals(getServiceType())) {
                return new HiveServiceAdapter(awsContext);
            }
            return new RangerServiceAdapter(awsContext);
        }

        @Override
        public String getServiceDefinitionResourcePath() {
            return "/ranger-servicedef-" + getServiceType() + ".json";
        }

        /**
         * Expose setLatestPolicies for testing.
         */
        @Override
        public void setLatestPolicies(ServicePolicies policies) {
            super.setLatestPolicies(policies);
        }
    }

    // -----------------------------------------------------------------------
    // Converter factory (same pattern as ServiceNamespaceIsolationPropertyTest)
    // -----------------------------------------------------------------------

    private RangerToCedarConverter buildConverter(String... serviceTypes) {
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        for (String serviceType : serviceTypes) {
            switch (serviceType) {
                case "lakeformation":
                    registry.put("lakeformation", new RangerServiceAdapter(AWS_CONTEXT));
                    break;
                case "hive":
                    registry.put("hive", new HiveServiceAdapter(AWS_CONTEXT));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown service type: " + serviceType);
            }
        }

        Map<String, String> userMap = new HashMap<>();
        userMap.put("alice", "arn:aws:iam::123456789012:user/alice");
        userMap.put("bob", "arn:aws:iam::123456789012:user/bob");
        userMap.put("charlie", "arn:aws:iam::123456789012:user/charlie");
        PrincipalMapper principalMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()), null);

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        return new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<List<RangerPolicy>> lakeformationPolicies() {
        return lakeformationPolicy().list().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<List<RangerPolicy>> hivePolicies() {
        return hivePolicy().list().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<Integer> failureCount() {
        return Arbitraries.integers().between(1, 5);
    }

    private Arbitrary<RangerPolicy> lakeformationPolicy() {
        Arbitrary<Long> policyIds = Arbitraries.longs().between(1, 500);
        Arbitrary<String> databases = Arbitraries.of("sales_db", "analytics_db", "finance_db");
        Arbitrary<String> tables = Arbitraries.of("orders", "customers", "transactions");
        Arbitrary<String> users = Arbitraries.of("alice", "bob", "charlie");
        Arbitrary<String> accessTypes = Arbitraries.of("select", "alter", "drop");

        return Combinators.combine(policyIds, databases, tables, users, accessTypes)
                .as((id, db, table, user, accessType) -> {
                    RangerPolicy policy = new RangerPolicy();
                    policy.setId(id);
                    policy.setName("lf_policy_" + id);
                    policy.setService("lakeformation");
                    policy.setPolicyType(0);

                    Map<String, RangerPolicyResource> resources = new HashMap<>();
                    RangerPolicyResource dbRes = new RangerPolicyResource();
                    dbRes.setValues(Collections.singletonList(db));
                    resources.put("database", dbRes);
                    RangerPolicyResource tableRes = new RangerPolicyResource();
                    tableRes.setValues(Collections.singletonList(table));
                    resources.put("table", tableRes);
                    policy.setResources(resources);

                    RangerPolicyItem item = new RangerPolicyItem();
                    RangerPolicyItemAccess access = new RangerPolicyItemAccess();
                    access.setType(accessType);
                    access.setIsAllowed(true);
                    item.setAccesses(Collections.singletonList(access));
                    item.setUsers(Collections.singletonList(user));
                    policy.setPolicyItems(Collections.singletonList(item));

                    return policy;
                });
    }

    private Arbitrary<RangerPolicy> hivePolicy() {
        Arbitrary<Long> policyIds = Arbitraries.longs().between(1000, 1500);
        Arbitrary<String> databases = Arbitraries.of("warehouse_db", "staging_db", "raw_db");
        Arbitrary<String> tables = Arbitraries.of("events", "logs", "metrics");
        Arbitrary<String> users = Arbitraries.of("alice", "bob", "charlie");
        Arbitrary<String> accessTypes = Arbitraries.of("select", "alter", "drop");

        return Combinators.combine(policyIds, databases, tables, users, accessTypes)
                .as((id, db, table, user, accessType) -> {
                    RangerPolicy policy = new RangerPolicy();
                    policy.setId(id);
                    policy.setName("hive_policy_" + id);
                    policy.setService("hive");
                    policy.setPolicyType(0);

                    Map<String, RangerPolicyResource> resources = new HashMap<>();
                    RangerPolicyResource dbRes = new RangerPolicyResource();
                    dbRes.setValues(Collections.singletonList(db));
                    resources.put("database", dbRes);
                    RangerPolicyResource tableRes = new RangerPolicyResource();
                    tableRes.setValues(Collections.singletonList(table));
                    resources.put("table", tableRes);
                    policy.setResources(resources);

                    RangerPolicyItem item = new RangerPolicyItem();
                    RangerPolicyItemAccess access = new RangerPolicyItemAccess();
                    access.setType(accessType);
                    access.setIsAllowed(true);
                    item.setAccesses(Collections.singletonList(access));
                    item.setUsers(Collections.singletonList(user));
                    policy.setPolicyItems(Collections.singletonList(item));

                    return policy;
                });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static CatalogResolver mockPassthroughResolver() {
        CatalogResolver resolver = mock(CatalogResolver.class);
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
}
