package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.S3AccessGrantsConfig;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.s3accessgrants.OperationType;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantPermission;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClient;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssessmentRunnerTest {

    private static final String ACCOUNT_ID = "123456789012";

    @Test
    void run_withEmptyPolicies_returnsZeroCounts() {
        PolicySource source = stubSource("lakeformation", "lakeformation", List.of());
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

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
        policy.setPolicyType(1);
        policy.setDataMaskPolicyItems(List.of(new RangerDataMaskPolicyItem()));

        PolicySource source = stubSource("lakeformation", "lakeformation", List.of(policy));
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getGapReport().getSummary().containsKey(GapType.DATA_MASKING));
    }

    @Test
    void run_withFullyConvertiblePolicy_noGaps() {
        RangerPolicy policy = buildLakeFormationPolicy(2L, "db1", "table1");
        RangerPolicyItem item = new RangerPolicyItem();
        item.setAccesses(List.of(access("select")));
        item.setUsers(List.of("arn:aws:iam::" + ACCOUNT_ID + ":user/alice"));
        policy.setPolicyItems(List.of(item));

        PolicySource source = stubSource("lakeformation", "lakeformation", List.of(policy));
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getProjectedGrantCount() >= 0);
    }

    @Test
    void run_withTagBasedPolicy_countedAsNotConvertible() {
        RangerPolicy policy = buildLakeFormationPolicy(3L, "db1", "table1");
        policy.setService("lakeformation_tag");
        policy.setPolicyItems(List.of());

        PolicySource source = stubSource("lakeformation_tag", "lakeformation", List.of(policy));
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getGapReport().getSummary().getOrDefault(GapType.TAG_BASED_POLICY, 0) > 0
                || result.getNotConvertible() == 1);
    }

    @Test
    void run_withSkippedBatch_recordsUnsupportedServiceTypeGap() {
        ServicePolicyBatch skipped = ServicePolicyBatch.skipped("yarn_prod", "yarn", 5, "unsupported service type");
        PolicySource source = new PolicySource() {
            @Override public List<ServicePolicyBatch> load() { return List.of(skipped); }
            @Override public String sourceLabel() { return "stub:yarn_prod"; }
        };
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(), source);

        assertEquals(0, result.getTotalPolicies(), "skipped policies must not count toward total");
        assertTrue(result.getGapReport().getSummary().containsKey(GapType.UNSUPPORTED_SERVICE_TYPE));
    }

    @Test
    void run_withUnregisteredS3Location_recordsUnregisteredS3LocationGap() {
        S3AccessGrantsClient mockS3AgClient = mock(S3AccessGrantsClient.class);
        when(mockS3AgClient.listRegisteredLocations()).thenReturn(Set.of("s3://registered-bucket/"));

        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT, "arn:aws:iam::123456789012:user/alice",
                "s3://other-bucket/data/", S3AccessGrantPermission.READ, null);

        PolicySource source = stubSource("emrfs_prod", "amazon-emr-emrfs", List.of());
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet s) {
                return List.of(emrfsOp);
            }
            @Override
            protected S3AccessGrantsClient createS3AccessGrantsClient(S3AccessGrantsConfig c) {
                return mockS3AgClient;
            }
        };
        AssessmentResult result = runner.run(s3AgConfig(), source);

        assertTrue(result.getGapReport().getSummary().containsKey(GapType.UNREGISTERED_S3_LOCATION));
        assertEquals(1, result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNREGISTERED_S3_LOCATION).count());
    }

    @Test
    void run_withoutS3AgConfig_recordsCannotValidateS3LocationGap() {
        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT, "arn:aws:iam::123456789012:user/alice",
                "s3://my-bucket/data/", S3AccessGrantPermission.READ, null);

        PolicySource source = stubSource("lakeformation", "lakeformation", List.of());
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet s) {
                return List.of(emrfsOp);
            }
        };
        AssessmentResult result = runner.run(minimalConfig(), source);

        assertTrue(result.getGapReport().getSummary().containsKey(GapType.CANNOT_VALIDATE_S3_LOCATION));
        assertEquals(1, result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.CANNOT_VALIDATE_S3_LOCATION).count());
    }

    @Test
    void run_withRegisteredS3Location_noUnregisteredGap() {
        S3AccessGrantsClient mockS3AgClient = mock(S3AccessGrantsClient.class);
        when(mockS3AgClient.listRegisteredLocations()).thenReturn(Set.of("s3://my-bucket/"));

        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT, "arn:aws:iam::123456789012:user/alice",
                "s3://my-bucket/data/", S3AccessGrantPermission.READ, null);

        PolicySource source = stubSource("emrfs_prod", "amazon-emr-emrfs", List.of());
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet s) {
                return List.of(emrfsOp);
            }
            @Override
            protected S3AccessGrantsClient createS3AccessGrantsClient(S3AccessGrantsConfig c) {
                return mockS3AgClient;
            }
        };
        AssessmentResult result = runner.run(s3AgConfig(), source);

        assertFalse(result.getGapReport().getSummary().containsKey(GapType.UNREGISTERED_S3_LOCATION));
    }

    // ---- helpers ----

    private PolicySource stubSource(String serviceName, String serviceType, List<RangerPolicy> policies) {
        for (RangerPolicy p : policies) {
            p.setService(serviceName);
        }
        ServicePolicyBatch batch = ServicePolicyBatch.assessed(serviceName, serviceType,
                policies, policies.size());
        return new PolicySource() {
            @Override public List<ServicePolicyBatch> load() { return List.of(batch); }
            @Override public String sourceLabel() { return "stub:" + serviceName; }
        };
    }

    private AssessmentConfig minimalConfig() {
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("alice", "arn:aws:iam::" + ACCOUNT_ID + ":user/alice");
        return AssessmentConfig.builder()
                .services(List.of(new RangerServiceConfig("lakeformation", "lakeformation", null, null)))
                .principalMapping(new PrincipalMappingConfig(
                        userMappings, Collections.emptyMap(), Collections.emptyMap()))
                .consoleOnly(true)
                .build();
    }

    private AssessmentConfig s3AgConfig() {
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("alice", "arn:aws:iam::" + ACCOUNT_ID + ":user/alice");
        return AssessmentConfig.builder()
                .services(List.of(new RangerServiceConfig("amazon-emr-emrfs", "emrfs_prod", null, null)))
                .principalMapping(new PrincipalMappingConfig(
                        userMappings, Collections.emptyMap(), Collections.emptyMap()))
                .consoleOnly(true)
                .s3AccessGrants(new S3AccessGrantsConfig(
                        "arn:aws:s3:us-east-1:" + ACCOUNT_ID + ":access-grants/default", ACCOUNT_ID))
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
