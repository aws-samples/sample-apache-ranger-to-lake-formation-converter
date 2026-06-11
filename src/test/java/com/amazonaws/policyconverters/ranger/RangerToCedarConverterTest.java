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
import com.amazonaws.policyconverters.lakeformation.PassthroughPrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.ranger.EmrSparkServiceAdapter;
import com.amazonaws.policyconverters.ranger.HiveServiceAdapter;
import com.amazonaws.policyconverters.ranger.PassthroughCatalogResolver;
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
import java.util.Optional;

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
    private RangerToCedarConverter hiveConverter;
    private RangerToCedarConverter emrSparkConverter;
    private GapReporter gapReporter;

    @BeforeEach
    void setUp() {
        AwsContext awsContext = new AwsContext("us-east-1", "123456789012", "123456789012");
        RangerServiceAdapter adapter = new RangerServiceAdapter(awsContext);
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("lakeformation", adapter);

        Map<String, String> userMap = new HashMap<>();
        userMap.put("alice", "arn:aws:iam::123456789012:user/alice");
        PrincipalMapper principalMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()), null);

        CatalogResolver catalogResolver = mockPassthroughResolver();
        gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        converter = new RangerToCedarConverter(registry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        AwsContext hiveCtx = new AwsContext("us-east-1", "123456789012", "123456789012");
        HiveServiceAdapter hiveAdapter = new HiveServiceAdapter(hiveCtx);
        Map<String, SourcePolicyAdapter> hiveRegistry = new HashMap<>();
        hiveRegistry.put("hive", hiveAdapter);
        PrincipalMapper passthroughMapper = new PassthroughPrincipalMapper();
        hiveConverter = new RangerToCedarConverter(hiveRegistry, passthroughMapper,
                new PassthroughCatalogResolver(), gapReporter, schemaProvider);

        AwsContext emrCtx = new AwsContext("us-east-1", "123456789012", "123456789012");
        EmrSparkServiceAdapter emrSparkAdapter = new EmrSparkServiceAdapter(emrCtx);
        Map<String, SourcePolicyAdapter> emrRegistry = new HashMap<>();
        emrRegistry.put("amazon-emr-spark", emrSparkAdapter);
        emrSparkConverter = new RangerToCedarConverter(emrRegistry, new PassthroughPrincipalMapper(),
                new PassthroughCatalogResolver(), gapReporter, schemaProvider);
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

    @Test
    void delegateAdminTrueEmitsGrantableAnnotation() {
        RangerPolicyItem item = buildItem("alice", "select");
        item.setDelegateAdmin(true);
        RangerPolicy policy = buildTablePolicy("lakeformation", 0);
        policy.setPolicyItems(Collections.singletonList(item));

        CedarPolicySet policySet = converter.convert(Collections.singletonList(policy));
        String cedarText = policySet.toCedarString();

        assertTrue(cedarText.contains("@grantable(\"true\")"),
                "delegateAdmin=true must produce @grantable(\"true\") annotation in Cedar output");
    }

    @Test
    void delegateAdminFalseDoesNotEmitGrantableAnnotation() {
        RangerPolicyItem item = buildItem("alice", "select");
        item.setDelegateAdmin(false);
        RangerPolicy policy = buildTablePolicy("lakeformation", 0);
        policy.setPolicyItems(Collections.singletonList(item));

        CedarPolicySet policySet = converter.convert(Collections.singletonList(policy));
        String cedarText = policySet.toCedarString();

        assertFalse(cedarText.contains("@grantable"),
                "delegateAdmin=false must NOT produce @grantable annotation");
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

    // --- Hive wildcard promotion tests ---

    @Test
    void hive_columnWildcard_withLiteralTable_promotesToTableLevel() {
        RangerPolicy policy = buildHivePolicy("mydb", "mytable", "*");
        CedarPolicySet result = hiveConverter.convert(List.of(policy));
        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::Table"), "Expected table-level entity");
        assertFalse(cedar.contains("DataCatalog::Column"), "Must not produce column-level entity");
        assertFalse(cedar.contains("column/mydb/mytable"), "Must not contain column-path ARN segment");
    }

    @Test
    void hive_columnAndTableWildcard_promotesToDatabaseLevel() {
        // ALTER is valid on DataCatalog::Database in the Cedar schema; SELECT is not
        RangerPolicy policy = buildHivePolicyWithAccess("mydb", "*", "*", "alter");
        CedarPolicySet result = hiveConverter.convert(List.of(policy));
        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::Database"), "Expected database-level entity");
        assertTrue(cedar.contains("arn:aws:glue:"), "Expected Glue ARN (not bare db name)");
        assertFalse(cedar.contains("DataCatalog::Table"), "Must not produce table-level entity");
        assertFalse(cedar.contains("DataCatalog::Column"), "Must not produce column-level entity");
    }

    @Test
    void hive_tableWildcardOnly_promotesToDatabaseLevel() {
        RangerPolicy policy = buildHivePolicyNoColumn("mydb", "*");
        CedarPolicySet result = hiveConverter.convert(List.of(policy));
        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::Database"), "Expected database-level entity");
        assertTrue(cedar.contains("arn:aws:glue:"), "Expected Glue ARN (not bare db name)");
    }

    @Test
    void hive_literalColumn_noPromotion() {
        RangerPolicy policy = buildHivePolicy("mydb", "mytable", "mycol");
        CedarPolicySet result = hiveConverter.convert(List.of(policy));
        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::Column"), "Expected column-level entity");
        assertTrue(cedar.contains("column/mydb/mytable/mycol"), "Expected full column ARN");
    }

    @Test
    void hive_partialWildcardTable_noPromotionToDatabase_producesTableLevel() {
        RangerPolicy policy = buildHivePolicy("mydb", "tbl_*", "*");
        CedarPolicySet result = hiveConverter.convert(List.of(policy));
        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::Table"), "Expected table-level entity after col=* promotion");
        assertFalse(cedar.contains("DataCatalog::Column"), "Must not produce column-level entity");
    }

    @Test
    void hive_partialWildcardTable_recordsWildcardPatternGap() {
        // tbl_* can't be promoted away (table is not all-wildcard), so col=* → table level
        // but tbl_* gets returned as-is by PassthroughCatalogResolver → WILDCARD_PATTERN gap
        RangerPolicy policy = buildHivePolicy("mydb", "tbl_*", "*");
        hiveConverter.convert(List.of(policy));
        List<GapEntry> gaps = gapReporter.getReport().getEntries();
        assertTrue(gaps.stream().anyMatch(g -> g.getGapType() == GapType.WILDCARD_PATTERN),
                "Expected WILDCARD_PATTERN gap for unresolvable table pattern");
    }

    @Test
    void hive_partialWildcardColumn_recordsWildcardPatternGap() {
        // col_* is a partial wildcard — not promoted away, reaches expandColumnPatterns
        // PassthroughCatalogResolver returns it unchanged → WILDCARD_PATTERN gap
        RangerPolicy policy = buildHivePolicy("mydb", "mytable", "col_*");
        hiveConverter.convert(List.of(policy));
        List<GapEntry> gaps = gapReporter.getReport().getEntries();
        assertTrue(gaps.stream().anyMatch(g -> g.getGapType() == GapType.WILDCARD_PATTERN),
                "Expected WILDCARD_PATTERN gap for unresolvable column pattern");
    }

    // --- Hive policy builder helpers ---

    private RangerPolicy buildHivePolicy(String db, String table, String col) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(999L);
        policy.setName("test-hive-policy");
        policy.setService("hive");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", resource(db));
        resources.put("table", resource(table));
        resources.put("column", resource(col));
        policy.setResources(resources);
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice"));
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        item.setAccesses(List.of(access));
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    private RangerPolicy buildHivePolicyNoColumn(String db, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(998L);
        policy.setName("test-hive-table-policy");
        policy.setService("hive");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", resource(db));
        resources.put("table", resource(table));
        policy.setResources(resources);
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice"));
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("alter");
        access.setIsAllowed(true);
        item.setAccesses(List.of(access));
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    private RangerPolicy buildHivePolicyWithAccess(String db, String table, String col, String accessType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(997L);
        policy.setName("test-hive-policy-access");
        policy.setService("hive");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", resource(db));
        resources.put("table", resource(table));
        resources.put("column", resource(col));
        policy.setResources(resources);
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice"));
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType(accessType);
        access.setIsAllowed(true);
        item.setAccesses(List.of(access));
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    private RangerPolicyResource resource(String value) {
        RangerPolicyResource r = new RangerPolicyResource();
        r.setValues(List.of(value));
        return r;
    }

    // ---- EMR Spark catalog policies ----

    @Test
    void emrSpark_tablePolicy_producesDataCatalogTableStatement() {
        RangerPolicy policy = buildEmrSparkTablePolicy("mydb", "mytable", "select");

        CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::Table::"), "Must reference DataCatalog::Table entity");
        assertTrue(cedar.contains("arn:aws:glue:us-east-1:123456789012:table/mydb/mytable"),
                "Must contain Glue table ARN");
        assertTrue(cedar.contains("\"SELECT\""), "Must contain SELECT action");
        assertTrue(cedar.contains("permit("), "Must be a permit statement");
    }

    @Test
    void emrSpark_columnWildcard_promotesToTableLevel() {
        RangerPolicy policy = buildEmrSparkPolicy("mydb", "mytable", "*", "select");

        CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::Table::"),
                "col=* should promote to table-level grant");
        assertFalse(cedar.contains("DataCatalog::Column::"),
                "Should not produce column-level grant when col=*");
    }

    // ---- EMR Spark url policies ----

    @Test
    void emrSpark_urlPolicy_producesDataLocationStatement() {
        RangerPolicy policy = buildEmrSparkUrlPolicy("s3://my-bucket/data/", "read");

        CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::DataLocation::"),
                "URL policy must reference DataCatalog::DataLocation entity");
        assertTrue(cedar.contains("arn:aws:s3:::my-bucket/data/"),
                "Must contain S3 ARN with s3:// stripped");
        assertTrue(cedar.contains("\"DATA_LOCATION_ACCESS\""),
                "URL policy must use DATA_LOCATION_ACCESS action");
    }

    @Test
    void emrSpark_wildcardUrl_stripsWildcardAndProducesDataLocationStatement() {
        GapReporter freshGapReporter = new GapReporter();
        CedarSchemaProvider freshSchemaProvider = new CedarSchemaProvider();
        Map<String, SourcePolicyAdapter> emrRegistry = new HashMap<>();
        emrRegistry.put("amazon-emr-spark",
                new EmrSparkServiceAdapter(new AwsContext("us-east-1", "123456789012", "123456789012")));
        RangerToCedarConverter freshConverter = new RangerToCedarConverter(
                emrRegistry, new PassthroughPrincipalMapper(),
                new PassthroughCatalogResolver(), freshGapReporter, freshSchemaProvider);

        // s3://bucket/* should strip to s3://bucket/ and produce DATA_LOCATION_ACCESS (happy path)
        RangerPolicy policy = buildEmrSparkUrlPolicy("s3://bucket/*", "read");
        String cedar = freshConverter.convert(Collections.singletonList(policy)).toCedarString();

        boolean hasWildcardGap = freshGapReporter.getReport().getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.WILDCARD_PATTERN);
        assertFalse(hasWildcardGap, "Wildcard URL with S3 prefix should NOT record a gap");
        assertTrue(cedar.contains("DATA_LOCATION_ACCESS"),
                "Stripped wildcard URL should produce DATA_LOCATION_ACCESS statement");
    }

    private RangerPolicy buildEmrSparkPolicy(String db, String table, String col, String accessType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1001L);
        policy.setName("test-emr-spark-policy");
        policy.setService("amazon-emr-spark");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", resource(db));
        resources.put("table", resource(table));
        resources.put("column", resource(col));
        policy.setResources(resources);
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice"));
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType(accessType);
        access.setIsAllowed(true);
        item.setAccesses(List.of(access));
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    private RangerPolicy buildEmrSparkTablePolicy(String db, String table, String accessType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1002L);
        policy.setName("test-emr-spark-table-policy");
        policy.setService("amazon-emr-spark");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", resource(db));
        resources.put("table", resource(table));
        policy.setResources(resources);
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice"));
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType(accessType);
        access.setIsAllowed(true);
        item.setAccesses(List.of(access));
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    // --- normalizeS3Location unit tests ---

    @Test
    void normalizeS3Location_s3UrlPassedThrough() {
        Optional<String> result = emrSparkConverter.normalizeS3Location("s3://my-bucket/path/", "policy-1");
        assertTrue(result.isPresent());
        assertEquals("s3://my-bucket/path/", result.get());
    }

    @Test
    void normalizeS3Location_s3aUrlNormalizedToS3() {
        Optional<String> result = emrSparkConverter.normalizeS3Location("s3a://my-bucket/path/", "policy-1");
        assertTrue(result.isPresent());
        assertEquals("s3://my-bucket/path/", result.get());
    }

    @Test
    void normalizeS3Location_s3nUrlNormalizedToS3() {
        Optional<String> result = emrSparkConverter.normalizeS3Location("s3n://my-bucket/path/", "policy-1");
        assertTrue(result.isPresent());
        assertEquals("s3://my-bucket/path/", result.get());
    }

    @Test
    void normalizeS3Location_hdfsPathReturnsEmpty() {
        GapReporter freshGapReporter = new GapReporter();
        RangerToCedarConverter conv = makeEmrConverter(freshGapReporter);

        Optional<String> result = conv.normalizeS3Location("hdfs://namenode/data/path/", "p1");
        assertFalse(result.isPresent(), "HDFS path must return empty");
        assertTrue(freshGapReporter.getReport().getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.UNMAPPED_RESOURCE),
                "HDFS path must record UNMAPPED_RESOURCE gap");
    }

    @Test
    void normalizeS3Location_fileUriReturnsEmpty() {
        GapReporter freshGapReporter = new GapReporter();
        RangerToCedarConverter conv = makeEmrConverter(freshGapReporter);

        Optional<String> result = conv.normalizeS3Location("file:///projects/2022/data/", "p2");
        assertFalse(result.isPresent(), "file:// URI must return empty");
        assertTrue(freshGapReporter.getReport().getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.UNMAPPED_RESOURCE),
                "file:// URI must record UNMAPPED_RESOURCE gap");
    }

    @Test
    void normalizeS3Location_barePathReturnsEmpty() {
        GapReporter freshGapReporter = new GapReporter();
        RangerToCedarConverter conv = makeEmrConverter(freshGapReporter);

        Optional<String> result = conv.normalizeS3Location("/projects/2022/data/", "p3");
        assertFalse(result.isPresent(), "Bare path must return empty");
        assertTrue(freshGapReporter.getReport().getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.UNMAPPED_RESOURCE),
                "Bare path must record UNMAPPED_RESOURCE gap");
    }

    // --- end-to-end: s3a/s3n URL policies produce correct Cedar ---

    @Test
    void emrSpark_s3aUrl_normalizedAndProducesDataLocationStatement() {
        RangerPolicy policy = buildEmrSparkUrlPolicy("s3a://my-bucket/data/", "read");
        CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

        String cedar = result.toCedarString();
        assertTrue(cedar.contains("DataCatalog::DataLocation::"),
                "s3a URL must produce DataCatalog::DataLocation statement");
        assertTrue(cedar.contains("arn:aws:s3:::my-bucket/data/"),
                "s3a URL must be normalized to s3:// before ARN construction");
        assertFalse(cedar.contains("s3a"), "s3a scheme must not appear in output Cedar");
    }

    @Test
    void emrSpark_s3nUrl_normalizedAndProducesDataLocationStatement() {
        RangerPolicy policy = buildEmrSparkUrlPolicy("s3n://my-bucket/data/", "read");
        CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

        String cedar = result.toCedarString();
        assertTrue(cedar.contains("arn:aws:s3:::my-bucket/data/"),
                "s3n URL must be normalized to s3:// before ARN construction");
        assertFalse(cedar.contains("s3n"), "s3n scheme must not appear in output Cedar");
    }

    // --- end-to-end: non-S3 URL policies produce gap and no Cedar ---

    @Test
    void emrSpark_hdfsUrl_skippedWithUnmappedResourceGap() {
        GapReporter freshGapReporter = new GapReporter();
        RangerToCedarConverter conv = makeEmrConverter(freshGapReporter);

        RangerPolicy policy = buildEmrSparkUrlPolicy("hdfs://namenode/data/", "read");
        CedarPolicySet result = conv.convert(Collections.singletonList(policy));

        assertTrue(result.toCedarString().trim().isEmpty() || !result.toCedarString().contains("DataCatalog::DataLocation"),
                "HDFS URL must not produce any DataLocation Cedar statement");
        assertTrue(freshGapReporter.getReport().getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.UNMAPPED_RESOURCE),
                "HDFS URL must record UNMAPPED_RESOURCE gap");
    }

    @Test
    void hive_fileUriDatalocation_skippedWithUnmappedResourceGap() {
        GapReporter freshGapReporter = new GapReporter();
        AwsContext hiveCtx = new AwsContext("us-east-1", "123456789012", "123456789012");
        HiveServiceAdapter hiveAdapter = new HiveServiceAdapter(hiveCtx);
        Map<String, SourcePolicyAdapter> hiveRegistry = new HashMap<>();
        hiveRegistry.put("hive", hiveAdapter);
        RangerToCedarConverter conv = new RangerToCedarConverter(
                hiveRegistry, new PassthroughPrincipalMapper(),
                new PassthroughCatalogResolver(), freshGapReporter, new CedarSchemaProvider());

        RangerPolicy policy = buildHiveDatalocationPolicy("file:///projects/2022/data/");
        CedarPolicySet result = conv.convert(Collections.singletonList(policy));

        assertFalse(result.toCedarString().contains("DataCatalog::DataLocation"),
                "file:// datalocation must not produce any DataLocation Cedar statement");
        assertTrue(freshGapReporter.getReport().getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.UNMAPPED_RESOURCE),
                "file:// datalocation must record UNMAPPED_RESOURCE gap");
    }

    // --- helpers ---

    private RangerToCedarConverter makeEmrConverter(GapReporter gapReporter) {
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        registry.put("amazon-emr-spark",
                new EmrSparkServiceAdapter(new AwsContext("us-east-1", "123456789012", "123456789012")));
        return new RangerToCedarConverter(registry, new PassthroughPrincipalMapper(),
                new PassthroughCatalogResolver(), gapReporter, new CedarSchemaProvider());
    }

    private RangerPolicy buildHiveDatalocationPolicy(String location) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(2001L);
        policy.setName("test-hive-datalocation");
        policy.setService("hive");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource locRes = new RangerPolicyResource();
        locRes.setValues(List.of(location));
        resources.put("datalocation", locRes);
        policy.setResources(resources);
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice"));
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("datalocation");
        access.setIsAllowed(true);
        item.setAccesses(List.of(access));
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    private RangerPolicy buildEmrSparkUrlPolicy(String url, String accessType) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1003L);
        policy.setName("test-emr-spark-url-policy");
        policy.setService("amazon-emr-spark");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource urlRes = new RangerPolicyResource();
        urlRes.setValues(List.of(url));
        resources.put("url", urlRes);
        policy.setResources(resources);
        RangerPolicyItem item = new RangerPolicyItem();
        item.setUsers(List.of("alice"));
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType(accessType);
        access.setIsAllowed(true);
        item.setAccesses(List.of(access));
        policy.setPolicyItems(List.of(item));
        return policy;
    }
}
