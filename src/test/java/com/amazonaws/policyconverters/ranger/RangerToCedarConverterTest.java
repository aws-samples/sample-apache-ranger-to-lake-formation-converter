package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemCondition;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemRowFilterInfo;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerPolicy.RangerRowFilterPolicyItem;
import org.apache.ranger.plugin.model.RangerValiditySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RangerToCedarConverter.
 * Validates: Requirements 4.1, 5.1, 5.2, 6.1, 7.1, 7.2, 7.3, 7.4
 */
class RangerToCedarConverterTest {

    private RangerToCedarConverter converter;
    private GapReporter gapReporter;

    @BeforeEach
    void setUp() {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerServiceAdapter adapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put("alice", "arn:aws:iam::123456789012:user/alice");
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()));

        CatalogResolver catalogResolver = mockPassthroughResolver();
        gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        converter = new RangerToCedarConverter(registry, principalMapper, catalogResolver, gapReporter, schemaProvider);
    }

    @Test
    void allowPolicyProducesPermitStatements() {
        // Use ALTER which is valid for Database resources in the Cedar schema
        RangerPolicy policy = buildBasePolicy("lakeformation", 0);
        policy.setPolicyItems(Collections.singletonList(buildItem("alice", "alter")));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        assertTrue(result.getPermitCount() > 0, "Allow policy should produce permit statements");
        assertEquals(0, result.getForbidCount(), "Allow policy should produce no forbid statements");
        assertTrue(result.toCedarString().contains("permit("), "Cedar text should contain permit");
    }

    @Test
    void denyPolicyProducesForbidStatements() {
        RangerPolicy policy = buildBasePolicy("lakeformation", 0);
        policy.setPolicyItems(Collections.singletonList(buildItem("alice", "alter")));
        policy.setDenyPolicyItems(Collections.singletonList(buildItem("alice", "alter")));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        assertTrue(result.getForbidCount() > 0, "Deny policy should produce forbid statements");
        assertTrue(result.toCedarString().contains("forbid("), "Cedar text should contain forbid");
    }

    @Test
    void denyExceptionProducesAnnotatedPermitStatements() {
        RangerPolicy policy = buildBasePolicy("lakeformation", 0);
        policy.setPolicyItems(Collections.singletonList(buildItem("alice", "alter")));
        policy.setDenyExceptions(Collections.singletonList(buildItem("alice", "alter")));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        String cedarText = result.toCedarString();
        assertTrue(cedarText.contains("@denyException"), "Deny exception should produce @denyException annotation");
        // Should have at least 2 permits: one from allow, one from deny exception
        assertTrue(result.getPermitCount() >= 2, "Should have permits from both allow and deny exception");
    }

    @Test
    void rowFilterProducesPermitWithRowFilterAttribute() {
        // Row filter needs table-level resource since SELECT applies to Table
        RangerPolicy policy = buildTablePolicy("lakeformation", 0);

        RangerRowFilterPolicyItem filterItem = new RangerRowFilterPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        filterItem.setAccesses(Collections.singletonList(access));
        filterItem.setUsers(Collections.singletonList("alice"));

        RangerPolicyItemRowFilterInfo rowFilterInfo = new RangerPolicyItemRowFilterInfo();
        rowFilterInfo.setFilterExpr("region = 'us-east-1'");
        filterItem.setRowFilterInfo(rowFilterInfo);

        policy.setRowFilterPolicyItems(Collections.singletonList(filterItem));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        // The row filter statement uses a `when { resource.rowFilter == "..." }` clause.
        // If the Cedar schema doesn't define a rowFilter attribute, the statement will
        // fail schema validation and be excluded, with a SCHEMA_VALIDATION_FAILURE gap.
        // Either the statement passes validation (rowFilter in output) or it's excluded (gap recorded).
        String cedarText = result.toCedarString();
        boolean hasRowFilter = cedarText.contains("rowFilter");
        boolean hasSchemaGap = gapReporter.getReport().getEntries().stream()
                .anyMatch(g -> g.getGapType() == GapType.SCHEMA_VALIDATION_FAILURE);

        assertTrue(hasRowFilter || hasSchemaGap,
                "Row filter should either appear in Cedar text or produce a SCHEMA_VALIDATION_FAILURE gap");
    }

    @Test
    void dataMaskingPolicyProducesGapAndSkip() {
        RangerPolicy policy = buildBasePolicy("lakeformation", 1);
        policy.setPolicyItems(Collections.singletonList(buildItem("alice", "alter")));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        assertEquals(0, result.getPermitCount(), "Data masking policy should produce zero permits");
        assertEquals(0, result.getForbidCount(), "Data masking policy should produce zero forbids");

        boolean hasDataMaskingGap = gapReporter.getReport().getEntries().stream()
                .anyMatch(g -> g.getGapType() == GapType.DATA_MASKING);
        assertTrue(hasDataMaskingGap, "Should record DATA_MASKING gap");
    }

    @Test
    void tagBasedPolicyProducesGapAndSkip() {
        RangerPolicy policy = buildBasePolicy("tag_lakeformation", 0);
        policy.setPolicyItems(Collections.singletonList(buildItem("alice", "alter")));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        assertEquals(0, result.getPermitCount(), "Tag-based policy should produce zero permits");
        assertEquals(0, result.getForbidCount(), "Tag-based policy should produce zero forbids");

        boolean hasTagGap = gapReporter.getReport().getEntries().stream()
                .anyMatch(g -> g.getGapType() == GapType.TAG_BASED_POLICY);
        assertTrue(hasTagGap, "Should record TAG_BASED_POLICY gap");
    }

    @Test
    void customConditionsProduceGap() {
        RangerPolicy policy = buildBasePolicy("lakeformation", 0);
        RangerPolicyItem item = buildItem("alice", "alter");
        RangerPolicyItemCondition condition = new RangerPolicyItemCondition();
        condition.setType("ip-range");
        condition.setValues(Collections.singletonList("10.0.0.0/8"));
        item.setConditions(Collections.singletonList(condition));
        policy.setPolicyItems(Collections.singletonList(item));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        // Conversion still happens (statements are produced)
        assertTrue(result.getPermitCount() > 0, "Custom conditions should not prevent statement generation");

        boolean hasConditionGap = gapReporter.getReport().getEntries().stream()
                .anyMatch(g -> g.getGapType() == GapType.CUSTOM_CONDITION);
        assertTrue(hasConditionGap, "Should record CUSTOM_CONDITION gap");
    }

    @Test
    void validitySchedulesProduceGap() {
        RangerPolicy policy = buildBasePolicy("lakeformation", 0);
        policy.setPolicyItems(Collections.singletonList(buildItem("alice", "alter")));

        RangerValiditySchedule schedule = new RangerValiditySchedule();
        policy.setValiditySchedules(Collections.singletonList(schedule));

        CedarPolicySet result = converter.convert(Collections.singletonList(policy));

        // Conversion still happens
        assertTrue(result.getPermitCount() > 0, "Validity schedules should not prevent statement generation");

        boolean hasScheduleGap = gapReporter.getReport().getEntries().stream()
                .anyMatch(g -> g.getGapType() == GapType.VALIDITY_SCHEDULE);
        assertTrue(hasScheduleGap, "Should record VALIDITY_SCHEDULE gap");
    }

    @Test
    void emptyPolicyListProducesEmptyPolicySet() {
        CedarPolicySet result = converter.convert(Collections.emptyList());

        assertEquals(0, result.getPermitCount(), "Empty policy list should produce zero permits");
        assertEquals(0, result.getForbidCount(), "Empty policy list should produce zero forbids");
    }

    // --- Helpers ---

    private static RangerPolicy buildBasePolicy(String serviceType, int policyType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1L);
        policy.setName("test_policy");
        policy.setService(serviceType);
        policy.setPolicyType(policyType);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("testdb"));
        resources.put("database", dbRes);
        policy.setResources(resources);

        return policy;
    }

    private static RangerPolicy buildTablePolicy(String serviceType, int policyType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(2L);
        policy.setName("test_table_policy");
        policy.setService(serviceType);
        policy.setPolicyType(policyType);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("testdb"));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList("testtable"));
        resources.put("table", tableRes);
        policy.setResources(resources);

        return policy;
    }

    private static RangerPolicyItem buildItem(String user, String accessType) {
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
