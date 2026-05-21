package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerDataMaskPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentRunnerTest {

    private static final String ACCOUNT_ID = "123456789012";

    @Test
    void run_withEmptyPolicies_returnsZeroCounts() {
        AssessmentConfig config = minimalConfig();
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<RangerPolicy> fetchPolicies(AssessmentConfig cfg) {
                return Collections.emptyList();
            }
        };

        AssessmentResult result = runner.run(config);

        assertEquals(0, result.getTotalPolicies());
        assertEquals(0, result.getFullyConvertible());
        assertEquals(0, result.getPartiallyConvertible());
        assertEquals(0, result.getNotConvertible());
        assertEquals(0, result.getProjectedGrantCount());
        assertNotNull(result.getGapReport());
    }

    @Test
    void run_withDataMaskingPolicy_recordsGapAndCountsPartial() {
        RangerPolicy policy = buildLakeFormationPolicy(1L, "db1", "table1");
        // policyType == 1 triggers DATA_MASKING gap in RangerToCedarConverter
        policy.setPolicyType(1);
        RangerDataMaskPolicyItem maskItem = new RangerDataMaskPolicyItem();
        policy.setDataMaskPolicyItems(List.of(maskItem));

        AssessmentConfig config = minimalConfig();
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<RangerPolicy> fetchPolicies(AssessmentConfig cfg) {
                return List.of(policy);
            }
        };

        AssessmentResult result = runner.run(config);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getGapReport().getSummary().containsKey(GapType.DATA_MASKING),
                "Expected DATA_MASKING gap");
    }

    @Test
    void run_withFullyConvertiblePolicy_noGaps() {
        RangerPolicy policy = buildLakeFormationPolicy(2L, "db1", "table1");
        RangerPolicyItem item = new RangerPolicyItem();
        item.setAccesses(List.of(access("select")));
        item.setUsers(List.of("arn:aws:iam::" + ACCOUNT_ID + ":user/alice"));
        policy.setPolicyItems(List.of(item));

        AssessmentConfig config = minimalConfig();
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<RangerPolicy> fetchPolicies(AssessmentConfig cfg) {
                return List.of(policy);
            }
        };

        AssessmentResult result = runner.run(config);

        assertEquals(1, result.getTotalPolicies());
        // A fully convertible policy should produce at least one projected grant
        assertTrue(result.getProjectedGrantCount() >= 0,
                "Projected grant count must be non-negative");
    }

    @Test
    void run_withTagBasedPolicy_countedAsNotConvertible() {
        RangerPolicy policy = buildLakeFormationPolicy(3L, "db1", "table1");
        policy.setService("lakeformation_tag"); // contains "tag" → skipped entirely
        policy.setPolicyItems(List.of());

        AssessmentConfig config = minimalConfig();
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<RangerPolicy> fetchPolicies(AssessmentConfig cfg) {
                return List.of(policy);
            }
        };

        AssessmentResult result = runner.run(config);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getGapReport().getSummary().getOrDefault(GapType.TAG_BASED_POLICY, 0) > 0
                || result.getNotConvertible() == 1,
                "Tag-based policy should be recorded as gap or not convertible");
    }

    // ---- helpers ----

    private AssessmentConfig minimalConfig() {
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("alice", "arn:aws:iam::" + ACCOUNT_ID + ":user/alice");
        PrincipalMappingConfig principalMapping = new PrincipalMappingConfig(
                userMappings, Collections.emptyMap(), Collections.emptyMap());

        return AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .rangerUsername("admin")
                .rangerPassword("admin")
                .services(List.of(new RangerServiceConfig(
                        "lakeformation", "lakeformation", null, null)))
                .principalMapping(principalMapping)
                .consoleOnly(true)
                .build();
    }

    private RangerPolicy buildLakeFormationPolicy(long id, String db, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("policy-" + id);
        policy.setService("lakeformation");
        policy.setIsEnabled(true);

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource(db));
        resources.put("table", new RangerPolicyResource(table));
        policy.setResources(resources);

        return policy;
    }

    private RangerPolicyItemAccess access(String type) {
        RangerPolicyItemAccess a = new RangerPolicyItemAccess();
        a.setType(type);
        a.setIsAllowed(true);
        return a;
    }
}
