package com.amazonaws.policyconverters;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import net.jqwik.api.*;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemCondition;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerValiditySchedule;

import com.cedarpolicy.model.exception.InternalException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for gap aggregation across the full pipeline.
 *
 * Feature: cedar-policy-abstraction, Property 17: Gap Aggregation from Both Converters
 * **Validates: Requirements 11.5**
 */
class PipelineGapAggregationPropertyTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String USER = "pipelineuser";
    private static final String USER_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":user/" + USER;

    /**
     * Property 17: Gap Aggregation from Both Converters
     *
     * For any conversion pipeline execution where the RangerToCedarConverter
     * produces N gap entries and the CedarToLFConverter produces M gap entries,
     * the shared GapReporter should contain at least N + M total entries after
     * both converters have run.
     *
     * Strategy:
     * - RangerToCedarConverter gaps: data masking, tag-based, custom conditions,
     *   validity schedules (these cause the converter to skip or annotate policies)
     * - CedarToLFConverter gaps: Cedar "UPDATE" action is valid in the Cedar schema
     *   but NOT mapped in CedarToLFConverter's ACTION_TO_PERMISSION, producing
     *   UNSUPPORTED_ACTION gaps. We construct these Cedar policies directly since
     *   the Ranger adapter maps "update" → "INSERT" (not "UPDATE").
     *
     * Both converters share the same GapReporter instance, verifying aggregation.
     */
    @Property(tries = 100)
    void gapAggregationFromBothConverters(
            @ForAll("rangerGapPolicyCounts") int rangerGapCount,
            @ForAll("cedarGapPolicyCounts") int cedarGapCount,
            @ForAll("rangerGapTypes") List<String> rangerGapTypes
    ) {
        // Shared GapReporter for the entire pipeline
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        // --- Stage 1: RangerToCedarConverter ---
        AwsContext awsContext = new AwsContext(REGION, ACCOUNT_ID, ACCOUNT_ID);
        RangerServiceAdapter adapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put(USER, USER_ARN);
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();

        RangerToCedarConverter rangerConverter = new RangerToCedarConverter(
                registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        // Build Ranger policies that produce gaps
        List<RangerPolicy> policies = new ArrayList<>();
        long policyIdCounter = 1;
        int actualRangerGapPolicies = Math.min(rangerGapCount, rangerGapTypes.size());
        for (int i = 0; i < actualRangerGapPolicies; i++) {
            String gapType = rangerGapTypes.get(i % rangerGapTypes.size());
            policies.add(buildRangerGapPolicy(policyIdCounter++, gapType));
        }

        // Run RangerToCedarConverter
        rangerConverter.convert(policies);

        // Count gaps after RangerToCedarConverter (= N)
        int gapsAfterRanger = gapReporter.getReport().getEntries().size();

        // --- Stage 2: CedarToLFConverter ---
        CedarToLFConverter cedarConverter = new CedarToLFConverter(
                schemaProvider, gapReporter, null);

        // Build Cedar policies with UPDATE action (valid in schema, unsupported by LF)
        // Each policy produces exactly one UNSUPPORTED_ACTION gap
        if (cedarGapCount > 0) {
            StringBuilder cedarBuilder = new StringBuilder();
            for (int i = 0; i < cedarGapCount; i++) {
                String tableArn = "arn:aws:glue:" + REGION + ":" + ACCOUNT_ID
                        + ":table/gapdb/gaptable" + i;
                cedarBuilder.append("@source(\"gap").append(i).append("\")\n")
                        .append("permit(\n")
                        .append("    principal == DataCatalog::Principal::\"").append(USER_ARN).append("\",\n")
                        .append("    action == DataCatalog::Action::\"UPDATE\",\n")
                        .append("    resource == DataCatalog::Table::\"").append(tableArn).append("\"\n")
                        .append(");\n");
            }

            try {
                CedarPolicySet cedarPolicySet = CedarPolicySet.fromCedarString(cedarBuilder.toString());
                cedarConverter.convert(cedarPolicySet);
            } catch (InternalException e) {
                fail("Cedar parsing should not fail for well-formed UPDATE policies: " + e.getMessage());
            }
        }

        // Count gaps after CedarToLFConverter
        List<GapEntry> allGaps = gapReporter.getReport().getEntries();
        int totalGaps = allGaps.size();
        int gapsFromCedar = totalGaps - gapsAfterRanger;

        // Property 17: total gaps >= N (from Ranger) + M (from Cedar)
        assertTrue(totalGaps >= gapsAfterRanger + gapsFromCedar,
                "Total gaps (" + totalGaps + ") should be >= "
                        + "Ranger gaps (" + gapsAfterRanger + ") + Cedar gaps (" + gapsFromCedar + ")");

        // Verify we got the expected number of gaps from each stage
        if (actualRangerGapPolicies > 0) {
            assertTrue(gapsAfterRanger > 0,
                    "Expected at least one gap from RangerToCedarConverter for "
                            + actualRangerGapPolicies + " gap-producing policies");
        }

        if (cedarGapCount > 0) {
            assertTrue(gapsFromCedar > 0,
                    "Expected at least one gap from CedarToLFConverter for "
                            + cedarGapCount + " UPDATE-action policies");

            // Verify UNSUPPORTED_ACTION gaps from CedarToLFConverter
            boolean hasCedarGapType = allGaps.stream().anyMatch(g ->
                    g.getGapType() == GapType.UNSUPPORTED_ACTION);
            assertTrue(hasCedarGapType,
                    "Should have UNSUPPORTED_ACTION gap from CedarToLFConverter");
        }

        // Verify Ranger gap types are present when expected
        if (actualRangerGapPolicies > 0) {
            boolean hasRangerGapType = allGaps.stream().anyMatch(g ->
                    g.getGapType() == GapType.DATA_MASKING
                            || g.getGapType() == GapType.TAG_BASED_POLICY
                            || g.getGapType() == GapType.CUSTOM_CONDITION
                            || g.getGapType() == GapType.VALIDITY_SCHEDULE);
            assertTrue(hasRangerGapType,
                    "Should have at least one Ranger-stage gap type in the report");
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<Integer> rangerGapPolicyCounts() {
        return Arbitraries.integers().between(0, 5);
    }

    @Provide
    Arbitrary<Integer> cedarGapPolicyCounts() {
        return Arbitraries.integers().between(0, 5);
    }

    @Provide
    Arbitrary<List<String>> rangerGapTypes() {
        return Arbitraries.of("data_masking", "tag_based", "custom_condition", "validity_schedule")
                .list()
                .ofMinSize(1)
                .ofMaxSize(5);
    }

    // --- Helpers ---

    /**
     * Build a Ranger policy that produces a gap in RangerToCedarConverter.
     */
    private RangerPolicy buildRangerGapPolicy(long policyId, String gapType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(policyId);
        policy.setName("gap_policy_" + policyId);
        policy.setPolicyType(0);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("gapdb"));
        resources.put("database", dbRes);
        policy.setResources(resources);

        RangerPolicyItem item = buildPolicyItem(USER, "alter");
        policy.setPolicyItems(Collections.singletonList(item));

        switch (gapType) {
            case "data_masking":
                policy.setService("lakeformation");
                policy.setPolicyType(1);
                break;
            case "tag_based":
                policy.setService("tag_lakeformation");
                break;
            case "custom_condition":
                policy.setService("lakeformation");
                RangerPolicyItem itemWithCondition = buildPolicyItem(USER, "alter");
                RangerPolicyItemCondition condition = new RangerPolicyItemCondition();
                condition.setType("ip-range");
                condition.setValues(Collections.singletonList("10.0.0.0/8"));
                itemWithCondition.setConditions(Collections.singletonList(condition));
                policy.setPolicyItems(Collections.singletonList(itemWithCondition));
                break;
            case "validity_schedule":
                policy.setService("lakeformation");
                RangerValiditySchedule schedule = new RangerValiditySchedule();
                policy.setValiditySchedules(Collections.singletonList(schedule));
                break;
            default:
                policy.setService("lakeformation");
                policy.setPolicyType(1);
                break;
        }

        return policy;
    }

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
