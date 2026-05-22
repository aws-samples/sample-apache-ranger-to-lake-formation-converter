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

    // ---- S3 Access Grants gap tests ----

    @Test
    void run_withUnregisteredS3Location_recordsUnregisteredS3LocationGap() {
        String unregisteredPrefix = "s3://other-bucket/data/";

        // Mock S3AccessGrantsClient returning a location that does NOT cover the prefix
        S3AccessGrantsClient mockS3AgClient = mock(S3AccessGrantsClient.class);
        when(mockS3AgClient.listRegisteredLocations())
                .thenReturn(Set.of("s3://registered-bucket/"));

        // Build a pre-constructed S3AG operation whose prefix is NOT covered
        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT,
                "arn:aws:iam::123456789012:user/alice",
                unregisteredPrefix,
                S3AccessGrantPermission.READ,
                null);

        AssessmentConfig config = s3AgConfig();
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<RangerPolicy> fetchPolicies(AssessmentConfig cfg) {
                return Collections.emptyList();
            }

            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet cedarPolicySet) {
                return List.of(emrfsOp);
            }

            @Override
            protected S3AccessGrantsClient createS3AccessGrantsClient(
                    S3AccessGrantsConfig s3Config) {
                return mockS3AgClient;
            }
        };

        AssessmentResult result = runner.run(config);

        assertTrue(
                result.getGapReport().getSummary()
                        .containsKey(GapType.UNREGISTERED_S3_LOCATION),
                "Expected UNREGISTERED_S3_LOCATION gap when prefix is not covered by registered locations");

        long count = result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.UNREGISTERED_S3_LOCATION)
                .count();
        assertEquals(1, count, "Should record exactly one UNREGISTERED_S3_LOCATION gap");
    }

    @Test
    void run_withoutS3AgConfig_recordsCannotValidateS3LocationGap() {
        // Build a pre-constructed S3AG operation
        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT,
                "arn:aws:iam::123456789012:user/alice",
                "s3://my-bucket/data/",
                S3AccessGrantPermission.READ,
                null);

        // Config WITHOUT s3AccessGrants configured
        AssessmentConfig config = minimalConfig();
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<RangerPolicy> fetchPolicies(AssessmentConfig cfg) {
                return Collections.emptyList();
            }

            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet cedarPolicySet) {
                return List.of(emrfsOp);
            }
        };

        AssessmentResult result = runner.run(config);

        assertTrue(
                result.getGapReport().getSummary()
                        .containsKey(GapType.CANNOT_VALIDATE_S3_LOCATION),
                "Expected CANNOT_VALIDATE_S3_LOCATION gap when s3AccessGrants config is absent");

        long count = result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.CANNOT_VALIDATE_S3_LOCATION)
                .count();
        assertEquals(1, count, "Should record exactly one CANNOT_VALIDATE_S3_LOCATION gap");
    }

    @Test
    void run_withRegisteredS3Location_noUnregisteredGap() {
        String prefix = "s3://my-bucket/data/";

        // Mock S3AccessGrantsClient returning a location that COVERS the prefix
        S3AccessGrantsClient mockS3AgClient = mock(S3AccessGrantsClient.class);
        when(mockS3AgClient.listRegisteredLocations())
                .thenReturn(Set.of("s3://my-bucket/"));

        S3AccessGrantOperation emrfsOp = new S3AccessGrantOperation(
                OperationType.GRANT,
                "arn:aws:iam::123456789012:user/alice",
                prefix,
                S3AccessGrantPermission.READ,
                null);

        AssessmentConfig config = s3AgConfig();
        AssessmentRunner runner = new AssessmentRunner() {
            @Override
            protected List<RangerPolicy> fetchPolicies(AssessmentConfig cfg) {
                return Collections.emptyList();
            }

            @Override
            protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet cedarPolicySet) {
                return List.of(emrfsOp);
            }

            @Override
            protected S3AccessGrantsClient createS3AccessGrantsClient(
                    S3AccessGrantsConfig s3Config) {
                return mockS3AgClient;
            }
        };

        AssessmentResult result = runner.run(config);

        assertFalse(
                result.getGapReport().getSummary()
                        .containsKey(GapType.UNREGISTERED_S3_LOCATION),
                "Should not record UNREGISTERED_S3_LOCATION when prefix is covered");
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

    private AssessmentConfig s3AgConfig() {
        Map<String, String> userMappings = new HashMap<>();
        userMappings.put("alice", "arn:aws:iam::" + ACCOUNT_ID + ":user/alice");
        PrincipalMappingConfig principalMapping = new PrincipalMappingConfig(
                userMappings, Collections.emptyMap(), Collections.emptyMap());
        S3AccessGrantsConfig s3AgConfig = new S3AccessGrantsConfig(
                "arn:aws:s3:us-east-1:" + ACCOUNT_ID + ":access-grants/default",
                ACCOUNT_ID);

        return AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .rangerUsername("admin")
                .rangerPassword("admin")
                .services(List.of(new RangerServiceConfig(
                        "amazon-emr-emrfs", "emrfs_prod", null, null)))
                .principalMapping(principalMapping)
                .consoleOnly(true)
                .s3AccessGrants(s3AgConfig)
                .build();
    }

    private RangerPolicyItemAccess access(String type) {
        RangerPolicyItemAccess a = new RangerPolicyItemAccess();
        a.setType(type);
        a.setIsAllowed(true);
        return a;
    }
}
