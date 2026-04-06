package com.amazonaws.policyconverters.ranger.converter;

import com.amazonaws.policyconverters.ranger.catalog.CatalogResolver;
import com.amazonaws.policyconverters.ranger.mapper.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.model.*;
import com.amazonaws.policyconverters.lakeformation.model.GapEntry.GapType;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.reporter.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PolicyConverter edge cases.
 * Validates: Requirements 2.1, 2.4, 2.5
 */
class PolicyConverterTest {

    private static final String CATALOG_ID = "123456789012";
    private static final String USER_ARN = "arn:aws:iam::123456789012:user/analyst";

    private PolicyConverter converter;
    private PrincipalMapper principalMapper;
    private CatalogResolver catalogResolver;
    private GapReporter gapReporter;

    @BeforeEach
    void setUp() {
        converter = new PolicyConverter(CATALOG_ID);
        principalMapper = buildMapper("analyst", USER_ARN);
        catalogResolver = mockPassthroughResolver();
        gapReporter = new GapReporter();
    }

    // -----------------------------------------------------------------------
    // Test 1: Known Ranger policy with "select" on db.table → one GRANT with SELECT
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("select access on db.table produces exactly one GRANT with SELECT permission")
    void selectAccessOnTableProducesOneGrantWithSelect() {
        RangerPolicy policy = buildPolicy(1L, "analytics", "events",
                Collections.singleton("select"), "analyst");

        List<LFPermissionOperation> ops = converter.convert(
                policy, principalMapper, catalogResolver, gapReporter);

        assertEquals(1, ops.size(), "Expected exactly one LF operation");
        LFPermissionOperation op = ops.get(0);
        assertEquals(OperationType.GRANT, op.getOperationType());
        assertEquals(USER_ARN, op.getPrincipalArn());
        assertEquals("1", op.getSourcePolicyId());
        assertEquals(EnumSet.of(LFPermission.SELECT), op.getPermissions());
        assertEquals("analytics", op.getResource().getDatabaseName());
        assertEquals("events", op.getResource().getTableName());
        assertEquals(CATALOG_ID, op.getResource().getCatalogId());
    }

    // -----------------------------------------------------------------------
    // Test 2: "all" access type expands to SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("all access type expands to SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE")
    void allAccessTypeExpandsToFullPermissionSet() {
        RangerPolicy policy = buildPolicy(2L, "mydb", "mytable",
                Collections.singleton("all"), "analyst");

        List<LFPermissionOperation> ops = converter.convert(
                policy, principalMapper, catalogResolver, gapReporter);

        assertEquals(1, ops.size());
        Set<LFPermission> expected = EnumSet.of(
                LFPermission.SELECT, LFPermission.INSERT, LFPermission.DELETE,
                LFPermission.ALTER, LFPermission.DROP, LFPermission.DESCRIBE);
        assertEquals(expected, ops.get(0).getPermissions());
    }

    // -----------------------------------------------------------------------
    // Test 3: Policy with only deny items → zero LF operations, DENY_POLICY gap
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("policy with only deny items produces zero operations and records DENY_POLICY gap")
    void denyOnlyPolicyProducesZeroOpsAndGap() {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(3L);
        policy.setName("deny_only_policy");
        policy.setService("hive");

        // Set up resources
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("securedb"));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList("secrets"));
        resources.put("table", tableRes);
        policy.setResources(resources);

        // No allow items — only deny items
        policy.setPolicyItems(Collections.<RangerPolicyItem>emptyList());

        RangerPolicyItem denyItem = new RangerPolicyItem();
        RangerPolicyItemAccess denyAccess = new RangerPolicyItemAccess();
        denyAccess.setType("select");
        denyAccess.setIsAllowed(true);
        denyItem.setAccesses(Collections.singletonList(denyAccess));
        denyItem.setUsers(Collections.singletonList("analyst"));
        policy.setDenyPolicyItems(Collections.singletonList(denyItem));

        List<LFPermissionOperation> ops = converter.convert(
                policy, principalMapper, catalogResolver, gapReporter);

        assertEquals(0, ops.size(), "Deny-only policy should produce zero LF operations");

        GapReport report = gapReporter.getReport();
        boolean hasDenyGap = report.getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.DENY_POLICY);
        assertTrue(hasDenyGap, "Gap report should contain a DENY_POLICY entry");
    }

    // -----------------------------------------------------------------------
    // Test 4: Policy with both allow and deny items → operations only for allow, deny in gap
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("mixed allow and deny items produces operations for allow and records deny in gap")
    void mixedAllowAndDenyProducesOpsForAllowOnly() {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(4L);
        policy.setName("mixed_policy");
        policy.setService("hive");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList("analytics"));
        resources.put("database", dbRes);
        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList("events"));
        resources.put("table", tableRes);
        policy.setResources(resources);

        // Allow item: select for analyst
        RangerPolicyItem allowItem = new RangerPolicyItem();
        RangerPolicyItemAccess allowAccess = new RangerPolicyItemAccess();
        allowAccess.setType("select");
        allowAccess.setIsAllowed(true);
        allowItem.setAccesses(Collections.singletonList(allowAccess));
        allowItem.setUsers(Collections.singletonList("analyst"));
        policy.setPolicyItems(Collections.singletonList(allowItem));

        // Deny item: drop for analyst
        RangerPolicyItem denyItem = new RangerPolicyItem();
        RangerPolicyItemAccess denyAccess = new RangerPolicyItemAccess();
        denyAccess.setType("drop");
        denyAccess.setIsAllowed(true);
        denyItem.setAccesses(Collections.singletonList(denyAccess));
        denyItem.setUsers(Collections.singletonList("analyst"));
        policy.setDenyPolicyItems(Collections.singletonList(denyItem));

        List<LFPermissionOperation> ops = converter.convert(
                policy, principalMapper, catalogResolver, gapReporter);

        // Should have operations only from the allow item
        assertEquals(1, ops.size());
        assertEquals(EnumSet.of(LFPermission.SELECT), ops.get(0).getPermissions());
        assertEquals(OperationType.GRANT, ops.get(0).getOperationType());

        // Gap report should contain DENY_POLICY
        GapReport report = gapReporter.getReport();
        boolean hasDenyGap = report.getEntries().stream()
                .anyMatch(e -> e.getGapType() == GapType.DENY_POLICY);
        assertTrue(hasDenyGap, "Gap report should record the deny policy item");
    }

    // -----------------------------------------------------------------------
    // Test 5: Null policy → empty list
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("null policy returns empty list")
    void nullPolicyReturnsEmptyList() {
        List<LFPermissionOperation> ops = converter.convert(
                null, principalMapper, catalogResolver, gapReporter);

        assertNotNull(ops);
        assertTrue(ops.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Test 6: Policy with no resources → empty list (malformed)
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("policy with no resources returns empty list")
    void policyWithNoResourcesReturnsEmptyList() {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(6L);
        policy.setName("no_resources_policy");
        policy.setService("hive");
        policy.setResources(null);

        // Add an allow item so it's not empty for other reasons
        RangerPolicyItem item = new RangerPolicyItem();
        RangerPolicyItemAccess access = new RangerPolicyItemAccess();
        access.setType("select");
        access.setIsAllowed(true);
        item.setAccesses(Collections.singletonList(access));
        item.setUsers(Collections.singletonList("analyst"));
        policy.setPolicyItems(Collections.singletonList(item));

        List<LFPermissionOperation> ops = converter.convert(
                policy, principalMapper, catalogResolver, gapReporter);

        assertNotNull(ops);
        assertTrue(ops.isEmpty(), "Policy with no resources should produce empty operations");
    }

    // -----------------------------------------------------------------------
    // Helper methods (matching patterns from PolicyConverterPropertyTest)
    // -----------------------------------------------------------------------

    private static RangerPolicy buildPolicy(Long id, String database, String table,
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

    private static PrincipalMapper buildMapper(String userName, String arn) {
        Map<String, String> userMap = new HashMap<>();
        userMap.put(userName, arn);
        return PrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.<String, String>emptyMap(),
                        Collections.<String, String>emptyMap()));
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
