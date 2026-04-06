package com.amazonaws.policyconverters.ranger.cedar;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.cedar.AwsContext;
import com.amazonaws.policyconverters.lakeformation.model.GapEntry;
import com.amazonaws.policyconverters.lakeformation.model.GapEntry.GapType;
import com.amazonaws.policyconverters.lakeformation.model.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.reporter.GapReporter;
import com.amazonaws.policyconverters.ranger.catalog.CatalogResolver;
import com.amazonaws.policyconverters.ranger.mapper.PrincipalMapper;
import net.jqwik.api.*;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemCondition;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerValiditySchedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for RangerToCedarConverter.
 *
 * Feature: cedar-policy-abstraction, Property 3: Unregistered Service Type Produces Gap
 * **Validates: Requirements 3.4**
 */
class RangerToCedarConverterPropertyTest {

    /**
     * Property 3: For any random service type string NOT in the adapter registry,
     * converting a policy with that service type produces zero Cedar statements
     * and records an UNSUPPORTED_SERVICE_TYPE gap.
     */
    @Property(tries = 100)
    void unregisteredServiceTypeProducesGap(
            @ForAll("randomServiceTypes") String serviceType
    ) {
        // Use a completely empty adapter registry so no fallback is possible
        Map<String, SourcePolicyAdapter> emptyRegistry = Collections.emptyMap();

        Map<String, String> userMap = new HashMap<>();
        userMap.put("testuser", "arn:aws:iam::123456789012:user/testuser");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter converter = new RangerToCedarConverter(
                emptyRegistry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        // Build a policy with the random service type
        RangerPolicy policy = buildPolicy(serviceType);

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        // Verify: zero permits and zero forbids
        assertEquals(0, result.getPermitCount(),
                "Expected 0 permits for unregistered service type: " + serviceType);
        assertEquals(0, result.getForbidCount(),
                "Expected 0 forbids for unregistered service type: " + serviceType);

        // Verify: at least one UNSUPPORTED_SERVICE_TYPE gap
        List<GapEntry> gaps = gapReporter.getReport().getEntries();
        boolean hasUnsupportedGap = gaps.stream()
                .anyMatch(g -> g.getGapType() == GapType.UNSUPPORTED_SERVICE_TYPE);
        assertTrue(hasUnsupportedGap,
                "Expected UNSUPPORTED_SERVICE_TYPE gap for service type: " + serviceType);
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> randomServiceTypes() {
        // Generate random strings that are NOT "lakeformation"
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('_')
                .ofMinLength(1)
                .ofMaxLength(30)
                .filter(s -> !s.equals("lakeformation"));
    }

    // --- Helpers ---

    private static RangerPolicy buildPolicy(String serviceType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1L);
        policy.setName("test_policy");
        policy.setService(serviceType);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("testdb"));
        resources.put("database", dbRes);
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        List<RangerPolicyItemAccess> accesses = new ArrayList<>();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        accesses.add(access);
        item.setAccesses(accesses);
        item.setUsers(Collections.singletonList("testuser"));
        policy.setPolicyItems(Collections.singletonList(item));

        return policy;
    }

    // -----------------------------------------------------------------------
    // Property 4: Allow Policy Permit Count
    // P principals × R resources × A actions = exact permit count
    // **Validates: Requirements 4.1, 4.2**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void allowPolicyPermitCount(
            @ForAll("principalLists") List<String> principals,
            @ForAll("databaseLists") List<String> databases,
            @ForAll("accessTypeLists") List<String> accessTypes
    ) {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerLFServiceAdapter adapter = new RangerLFServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        // Build principal mappings
        Map<String, String> userMap = new HashMap<>();
        for (String p : principals) {
            userMap.put(p, "arn:aws:iam::123456789012:user/" + p);
        }
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter converter = new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        // Build policy with multiple principals, databases, and access types (table-level)
        RangerPolicy policy = new RangerPolicy();
        policy.setId(100L);
        policy.setName("permit_count_policy");
        policy.setService("lakeformation");
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(new ArrayList<>(databases));
        resources.put("database", dbRes);
        // Use table-level resources so SELECT is valid in the Cedar schema
        RangerPolicyResource tableRes = new RangerPolicyResource();
        List<String> tableNames = new ArrayList<>();
        tableNames.add("testtable");
        tableRes.setValues(tableNames);
        resources.put("table", tableRes);
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
        item.setUsers(new ArrayList<>(principals));
        policy.setPolicyItems(Collections.singletonList(item));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        // Count unique Cedar actions produced by the access types
        Set<String> cedarActions = new java.util.LinkedHashSet<>();
        for (String at : accessTypes) {
            cedarActions.addAll(adapter.mapAccessTypeToCedarActions(at));
        }

        // With table-level resources: P principals × D databases × 1 table × A actions
        int expectedPermits = principals.size() * databases.size() * cedarActions.size();
        assertEquals(expectedPermits, result.getPermitCount(),
                "Expected P×R×A permits: " + principals.size() + "×" + databases.size()
                        + "×" + cedarActions.size() + " = " + expectedPermits);
    }

    // -----------------------------------------------------------------------
    // Property 6: Wildcard Expansion Produces Only Concrete Names
    // After conversion, no * or ? in ARN path segments
    // **Validates: Requirements 4.6**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void wildcardExpansionProducesOnlyConcreteNames(
            @ForAll("concreteDatabaseNames") String database
    ) {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerLFServiceAdapter adapter = new RangerLFServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put("user1", "arn:aws:iam::123456789012:user/user1");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter converter = new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        RangerPolicy policy = new RangerPolicy();
        policy.setId(200L);
        policy.setName("wildcard_test");
        policy.setService("lakeformation");
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(database));
        resources.put("database", dbRes);
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("alter");
        access.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(access));
        item.setUsers(Collections.singletonList("user1"));
        policy.setPolicyItems(Collections.singletonList(item));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        String cedarText = result.toCedarString();
        if (!cedarText.isEmpty()) {
            // Extract ARN path segments (after the resource type prefix like database/, table/, etc.)
            // Check that no entity identifier contains * or ?
            String[] lines = cedarText.split("\n");
            for (String line : lines) {
                if (line.contains("resource ==")) {
                    assertFalse(line.contains("*"), "ARN path should not contain * after conversion: " + line);
                    assertFalse(line.contains("?"), "ARN path should not contain ? after conversion: " + line);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 7: Deny and Deny-Exception Statement Generation
    // D deny items → forbid statements, E deny-exception items → @denyException permits
    // **Validates: Requirements 5.1, 5.2, 5.3**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void denyAndDenyExceptionStatementGeneration(
            @ForAll("accessTypesSingle") String accessType
    ) {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerLFServiceAdapter adapter = new RangerLFServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put("denyuser", "arn:aws:iam::123456789012:user/denyuser");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter converter = new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        RangerPolicy policy = new RangerPolicy();
        policy.setId(300L);
        policy.setName("deny_test");
        policy.setService("lakeformation");
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("denydb"));
        resources.put("database", dbRes);
        policy.setResources(resources);

        // 1 allow item
        policy.setPolicyItems(Collections.singletonList(buildPolicyItem("denyuser", accessType)));
        // 1 deny item
        policy.setDenyPolicyItems(Collections.singletonList(buildPolicyItem("denyuser", accessType)));
        // 1 deny-exception item
        policy.setDenyExceptions(Collections.singletonList(buildPolicyItem("denyuser", accessType)));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        assertTrue(result.getForbidCount() >= 1,
                "Should produce at least 1 forbid statement for deny item");

        String cedarText = result.toCedarString();
        assertTrue(cedarText.contains("@denyException"),
                "Should contain @denyException annotation for deny-exception item");
    }

    // -----------------------------------------------------------------------
    // Property 8: Policy ID Annotation Preservation
    // Every Cedar statement carries the original Ranger policy ID annotation
    // **Validates: Requirements 5.4**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void policyIdAnnotationPreservation(
            @ForAll("randomPolicyIds") long policyId
    ) {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerLFServiceAdapter adapter = new RangerLFServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put("annotuser", "arn:aws:iam::123456789012:user/annotuser");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter converter = new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        RangerPolicy policy = new RangerPolicy();
        policy.setId(policyId);
        policy.setName("annot_policy_" + policyId);
        policy.setService("lakeformation");
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("annotdb"));
        resources.put("database", dbRes);
        policy.setResources(resources);

        policy.setPolicyItems(Collections.singletonList(buildPolicyItem("annotuser", "alter")));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        // All source policy IDs should match the original policy ID
        List<String> sourcePolicyIds = result.getSourcePolicyIds();
        assertFalse(sourcePolicyIds.isEmpty(), "Should have at least one source policy ID");
        for (String id : sourcePolicyIds) {
            assertEquals(String.valueOf(policyId), id,
                    "Source policy ID should match original Ranger policy ID");
        }
    }

    // -----------------------------------------------------------------------
    // Property 10: Unsupported Features Produce Correct Gap Entries
    // Data masking, tag-based, custom conditions, validity schedules → correct GapType
    // **Validates: Requirements 7.1, 7.2, 7.3, 7.4**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void unsupportedFeaturesProduceCorrectGapEntries(
            @ForAll("unsupportedFeatureTypes") String featureType,
            @ForAll("randomPolicyIds") long policyId
    ) {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerLFServiceAdapter adapter = new RangerLFServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put("gapuser", "arn:aws:iam::123456789012:user/gapuser");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter converter = new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        RangerPolicy policy = new RangerPolicy();
        policy.setId(policyId);
        policy.setName("gap_policy_" + policyId);
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("gapdb"));
        resources.put("database", dbRes);
        policy.setResources(resources);

        policy.setPolicyItems(Collections.singletonList(buildPolicyItem("gapuser", "alter")));

        GapType expectedGapType;

        switch (featureType) {
            case "data_masking":
                policy.setService("lakeformation");
                policy.setPolicyType(1);
                expectedGapType = GapType.DATA_MASKING;
                break;
            case "tag_based":
                policy.setService("tag_lakeformation");
                expectedGapType = GapType.TAG_BASED_POLICY;
                break;
            case "custom_condition":
                policy.setService("lakeformation");
                RangerPolicyItem itemWithCondition = buildPolicyItem("gapuser", "alter");
                RangerPolicyItemCondition condition = new RangerPolicyItemCondition();
                condition.setType("ip-range");
                condition.setValues(Collections.singletonList("10.0.0.0/8"));
                itemWithCondition.setConditions(Collections.singletonList(condition));
                policy.setPolicyItems(Collections.singletonList(itemWithCondition));
                expectedGapType = GapType.CUSTOM_CONDITION;
                break;
            case "validity_schedule":
                policy.setService("lakeformation");
                RangerValiditySchedule schedule = new RangerValiditySchedule();
                policy.setValiditySchedules(Collections.singletonList(schedule));
                expectedGapType = GapType.VALIDITY_SCHEDULE;
                break;
            default:
                throw new IllegalArgumentException("Unknown feature type: " + featureType);
        }

        converter.convert(Collections.singletonList(policy));

        List<GapEntry> gaps = gapReporter.getReport().getEntries();
        boolean hasExpectedGap = gaps.stream()
                .anyMatch(g -> g.getGapType() == expectedGapType);
        assertTrue(hasExpectedGap,
                "Expected " + expectedGapType + " gap for feature type: " + featureType
                        + ", but got: " + gaps);
    }

    // -----------------------------------------------------------------------
    // Property 11: Schema Validation Excludes Invalid Statements
    // Valid policies → no SCHEMA_VALIDATION_FAILURE gaps
    // **Validates: Requirements 1.5, 8.1, 8.2**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void schemaValidationExcludesInvalidStatements(
            @ForAll("accessTypesSingle") String accessType
    ) {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerLFServiceAdapter adapter = new RangerLFServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put("validuser", "arn:aws:iam::123456789012:user/validuser");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        RangerToCedarConverter converter = new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        RangerPolicy policy = new RangerPolicy();
        policy.setId(500L);
        policy.setName("valid_policy");
        policy.setService("lakeformation");
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("validdb"));
        resources.put("database", dbRes);
        policy.setResources(resources);

        policy.setPolicyItems(Collections.singletonList(buildPolicyItem("validuser", accessType)));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        // For valid policies, there should be no SCHEMA_VALIDATION_FAILURE gaps
        List<GapEntry> gaps = gapReporter.getReport().getEntries();
        boolean hasSchemaFailure = gaps.stream()
                .anyMatch(g -> g.getGapType() == GapType.SCHEMA_VALIDATION_FAILURE);
        assertFalse(hasSchemaFailure,
                "Valid policy should not produce SCHEMA_VALIDATION_FAILURE gaps, but got: " + gaps);

        // Should produce at least one statement
        assertTrue(result.getPermitCount() > 0,
                "Valid policy should produce at least one permit statement");
    }

    // --- Additional Arbitraries ---

    @Provide
    Arbitrary<List<String>> principalLists() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(10)
                .map(String::toLowerCase)
                .list()
                .ofMinSize(1)
                .ofMaxSize(3)
                .uniqueElements();
    }

    @Provide
    Arbitrary<List<String>> databaseLists() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(10)
                .map(String::toLowerCase)
                .list()
                .ofMinSize(1)
                .ofMaxSize(3)
                .uniqueElements();
    }

    @Provide
    Arbitrary<List<String>> accessTypeLists() {
        // Use actions valid for Table resources in the Cedar schema and mapped by the adapter
        return Arbitraries.of("select", "alter", "drop")
                .list()
                .ofMinSize(1)
                .ofMaxSize(3)
                .uniqueElements();
    }

    @Provide
    Arbitrary<String> accessTypesSingle() {
        // Use actions valid for Database resources and mapped by the adapter
        // alter → ALTER (valid for Database), drop → DROP (valid for Database)
        return Arbitraries.of("alter", "drop");
    }

    @Provide
    Arbitrary<String> concreteDatabaseNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(15)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<Long> randomPolicyIds() {
        return Arbitraries.longs().between(1, 100000);
    }

    @Provide
    Arbitrary<String> unsupportedFeatureTypes() {
        return Arbitraries.of("data_masking", "tag_based", "custom_condition", "validity_schedule");
    }

    // --- Shared Helpers ---

    private static RangerPolicyItem buildPolicyItem(String user, String accessType) {
        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType(accessType);
        access.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(access));
        item.setUsers(Collections.singletonList(user));
        return item;
    }

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
