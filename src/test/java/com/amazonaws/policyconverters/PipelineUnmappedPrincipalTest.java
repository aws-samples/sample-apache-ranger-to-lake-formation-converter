package com.amazonaws.policyconverters;

import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.PolicyConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end pipeline tests verifying that unmapped Ranger principals never
 * produce Lake Formation grants.
 *
 * Security invariant: an unmapped user MUST receive zero LF permissions.
 * No exception should propagate to the caller; the policy item is silently
 * skipped (with a log warning from StaticPrincipalMapper).
 *
 * Observability note: the current implementation does not emit a structured
 * GapReporter entry for unmapped principals — it relies on SLF4J WARN logs
 * instead. A future improvement would be to record a gap entry so operators
 * can identify coverage gaps in their principal mapping config.
 */
class PipelineUnmappedPrincipalTest {

    private static final String CATALOG_ID = "123456789012";

    private PolicyConverter policyConverter;
    private StaticPrincipalMapper allUnmappedMapper;
    private CatalogResolver passthroughResolver;
    private GapReporter gapReporter;

    @BeforeEach
    void setUp() {
        policyConverter = new PolicyConverter(CATALOG_ID);

        // Empty mapping — every user/group/role is unmapped
        allUnmappedMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(
                        Collections.<String, String>emptyMap(),
                        Collections.<String, String>emptyMap(),
                        Collections.<String, String>emptyMap()),
                null);

        passthroughResolver = mock(CatalogResolver.class);
        when(passthroughResolver.expandDatabases(anyString()))
                .thenAnswer(inv -> Collections.singletonList((String) inv.getArgument(0)));
        when(passthroughResolver.expandTables(anyString(), anyString()))
                .thenAnswer(inv -> Collections.singletonList((String) inv.getArgument(1)));
        when(passthroughResolver.expandColumns(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Collections.singletonList((String) inv.getArgument(2)));

        gapReporter = new GapReporter();
    }

    /**
     * Security test: a policy whose every principal is absent from the mapping
     * config must produce zero LFPermissionOperations and must not throw.
     *
     * This is the core security invariant: an unmapped user must NEVER receive
     * a Lake Formation grant — not even accidentally via a wildcard or fallback.
     */
    @Test
    void allUnmappedUsersProduceZeroGrantsAndNoException() {
        RangerPolicy policy = buildPolicy(1L, "analytics", "events",
                Collections.singleton("select"),
                Arrays.asList("unmapped_user_1", "unmapped_user_2"));

        List<LFPermissionOperation> ops = assertDoesNotThrow(
                () -> policyConverter.convert(policy, allUnmappedMapper, passthroughResolver, gapReporter),
                "Converting a policy with all-unmapped principals must never throw");

        assertEquals(0, ops.size(),
                "All principals unmapped → zero LFPermissionOperations. " +
                "An unmapped user must never receive any grant.");
    }

    /**
     * Security test: when a policy item lists both a mapped and an unmapped user,
     * only the mapped user should receive a Lake Formation grant.
     *
     * The unmapped user must be silently skipped — it must not block the mapped
     * user from receiving its correct grant, nor must it produce a spurious grant.
     */
    @Test
    void mixedMappedAndUnmappedProducesGrantOnlyForMappedUser() {
        StaticPrincipalMapper partialMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(
                        Collections.singletonMap(
                                "mapped_user", "arn:aws:iam::123456789012:role/MappedRole"),
                        Collections.<String, String>emptyMap(),
                        Collections.<String, String>emptyMap()),
                null);

        RangerPolicy policy = buildPolicy(2L, "analytics", "events",
                Collections.singleton("select"),
                Arrays.asList("mapped_user", "unmapped_user"));

        List<LFPermissionOperation> ops = policyConverter.convert(
                policy, partialMapper, passthroughResolver, gapReporter);

        assertEquals(1, ops.size(),
                "Only the mapped user should produce a grant");
        assertEquals("arn:aws:iam::123456789012:role/MappedRole",
                ops.get(0).getPrincipalArn(),
                "The single grant must target the mapped user's ARN");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static RangerPolicy buildPolicy(long id, String db, String table,
                                             Set<String> accessTypes, List<String> users) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("test-policy-" + id);
        policy.setService("hive");
        policy.setIsEnabled(true);

        Map<String, RangerPolicy.RangerPolicyResource> resources = new HashMap<>();

        RangerPolicy.RangerPolicyResource dbRes = new RangerPolicy.RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(db));
        resources.put("database", dbRes);

        RangerPolicy.RangerPolicyResource tableRes = new RangerPolicy.RangerPolicyResource();
        tableRes.setValues(Collections.singletonList(table));
        resources.put("table", tableRes);

        policy.setResources(resources);

        RangerPolicy.RangerPolicyItem item = new RangerPolicy.RangerPolicyItem();
        item.setUsers(new ArrayList<>(users));

        List<RangerPolicy.RangerPolicyItemAccess> accesses = new ArrayList<>();
        for (String at : accessTypes) {
            RangerPolicy.RangerPolicyItemAccess a = new RangerPolicy.RangerPolicyItemAccess();
            a.setType(at);
            a.setIsAllowed(true);
            accesses.add(a);
        }
        item.setAccesses(accesses);

        policy.setPolicyItems(Collections.singletonList(item));
        policy.setDenyPolicyItems(Collections.<RangerPolicy.RangerPolicyItem>emptyList());

        return policy;
    }
}
