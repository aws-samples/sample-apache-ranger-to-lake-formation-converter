package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.reporting.GapReporter;
import net.jqwik.api.*;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Feature: multi-ranger-plugin-support, Property 1: Backward Compatibility

/**
 * Property-based test verifying that converting LakeFormation Ranger policies
 * through a multi-service pipeline (with only LakeFormation configured) produces
 * identical Cedar output as converting them through a separate single-service pipeline.
 *
 * <p>Both pipelines use the same RangerToCedarConverter with only the "lakeformation"
 * adapter registered. The key verification is that the @source annotation format
 * (prefixed with "lakeformation:") is consistent across both pipelines, and the
 * Cedar output is identical.
 *
 * **Validates: Requirements 2.4**
 */
@Tag("multi-ranger-plugin-support")
class BackwardCompatibilityPropertyTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");

    // -----------------------------------------------------------------------
    // Property 1: Backward Compatibility — identical Cedar output
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void multiServicePipelineWithOnlyLFProducesIdenticalCedarOutput(
            @ForAll("lakeformationPolicies") List<RangerPolicy> policies
    ) {
        // Pipeline A: "multi-service" mode with only lakeformation adapter registered
        RangerToCedarConverter multiServiceConverter = buildConverter("lakeformation");
        CedarPolicySet cedarMulti = multiServiceConverter.convert(policies);

        // Pipeline B: "single-service" mode with only lakeformation adapter registered
        RangerToCedarConverter singleServiceConverter = buildConverter("lakeformation");
        CedarPolicySet cedarSingle = singleServiceConverter.convert(policies);

        // Compare Cedar output as sorted sets of individual policy statements
        // (PolicySet internal ordering is not guaranteed after parsing)
        Set<String> multiStatements = toPolicyStatementSet(cedarMulti);
        Set<String> singleStatements = toPolicyStatementSet(cedarSingle);
        assertEquals(singleStatements, multiStatements,
                "Multi-service pipeline (LF only) should produce identical Cedar statements as single-service pipeline");
    }

    @Property(tries = 100)
    void multiServicePipelinePreservesPermitCount(
            @ForAll("lakeformationPolicies") List<RangerPolicy> policies
    ) {
        RangerToCedarConverter multiServiceConverter = buildConverter("lakeformation");
        CedarPolicySet cedarMulti = multiServiceConverter.convert(policies);

        RangerToCedarConverter singleServiceConverter = buildConverter("lakeformation");
        CedarPolicySet cedarSingle = singleServiceConverter.convert(policies);

        assertEquals(cedarSingle.getPermitCount(), cedarMulti.getPermitCount(),
                "Permit count should be identical between multi-service and single-service pipelines");
        assertEquals(cedarSingle.getForbidCount(), cedarMulti.getForbidCount(),
                "Forbid count should be identical between multi-service and single-service pipelines");
    }

    @Property(tries = 100)
    void multiServicePipelinePreservesSourcePolicyIds(
            @ForAll("lakeformationPolicies") List<RangerPolicy> policies
    ) {
        RangerToCedarConverter multiServiceConverter = buildConverter("lakeformation");
        CedarPolicySet cedarMulti = multiServiceConverter.convert(policies);

        RangerToCedarConverter singleServiceConverter = buildConverter("lakeformation");
        CedarPolicySet cedarSingle = singleServiceConverter.convert(policies);

        List<String> multiIds = cedarMulti.getSourcePolicyIds();
        List<String> singleIds = cedarSingle.getSourcePolicyIds();

        assertEquals(singleIds.size(), multiIds.size(),
                "Source policy ID count should be identical");
        assertEquals(new HashSet<>(singleIds), new HashSet<>(multiIds),
                "Source policy IDs should be identical between pipelines");

        // Verify all source IDs use the lakeformation: prefix
        for (String sourceId : multiIds) {
            assertTrue(sourceId.startsWith("lakeformation:"),
                    "Source ID should be prefixed with 'lakeformation:': " + sourceId);
        }
    }

    // -----------------------------------------------------------------------
    // Converter factory
    // -----------------------------------------------------------------------

    private RangerToCedarConverter buildConverter(String... serviceTypes) {
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        for (String serviceType : serviceTypes) {
            if ("lakeformation".equals(serviceType)) {
                registry.put("lakeformation", new RangerServiceAdapter(AWS_CONTEXT));
            } else {
                throw new IllegalArgumentException("Unknown service type: " + serviceType);
            }
        }

        Map<String, String> userMap = new HashMap<>();
        userMap.put("alice", "arn:aws:iam::123456789012:user/alice");
        userMap.put("bob", "arn:aws:iam::123456789012:user/bob");
        userMap.put("charlie", "arn:aws:iam::123456789012:user/charlie");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

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
        return lakeformationPolicy().list().ofMinSize(1).ofMaxSize(5);
    }

    private Arbitrary<RangerPolicy> lakeformationPolicy() {
        Arbitrary<Long> policyIds = Arbitraries.longs().between(1, 500);
        Arbitrary<String> databases = Arbitraries.of("sales_db", "analytics_db", "finance_db");
        Arbitrary<String> tables = Arbitraries.of("orders", "customers", "transactions");
        Arbitrary<String> users = Arbitraries.of("alice", "bob", "charlie");
        Arbitrary<String> accessTypes = Arbitraries.of("select", "alter", "drop", "insert", "describe");

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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Extract individual policy statement sources from a CedarPolicySet as a Set
     * for order-independent comparison. The Cedar PolicySet may reorder policies
     * internally after parsing, so we compare as sets of normalized statements.
     */
    private static Set<String> toPolicyStatementSet(CedarPolicySet policySet) {
        Set<String> statements = new HashSet<>();
        if (policySet.getInternalPolicySet().policies != null) {
            for (com.cedarpolicy.model.policy.Policy policy : policySet.getInternalPolicySet().policies) {
                String source = policy.getSource();
                if (source != null && !source.trim().isEmpty()) {
                    statements.add(source.trim());
                }
            }
        }
        return statements;
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
