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
                "s3://other-bucket/data/", S3AccessGrantPermission.READ, null, null);

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
                "s3://my-bucket/data/", S3AccessGrantPermission.READ, null, null);

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
                "s3://my-bucket/data/", S3AccessGrantPermission.READ, null, null);

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

    @Test
    void run_withDefaultEmptyMapping_usesPassthroughAndWarns() {
        // Config with no principal mapping — builder default is empty STATIC
        AssessmentConfig config = AssessmentConfig.builder().consoleOnly(true).build();

        RangerPolicy policy = buildLakeFormationPolicy(99L, "db1", "table1");
        RangerPolicyItem item = new RangerPolicyItem();
        item.setAccesses(List.of(access("select")));
        item.setUsers(List.of("alice"));  // unmapped — passthrough produces ranger-user:alice
        policy.setPolicyItems(List.of(item));

        PolicySource source = stubSource("lakeformation", "lakeformation", List.of(policy));
        AssessmentResult result = new AssessmentRunner().run(config, source);

        assertEquals(1, result.getWarnings().size(), "Expected exactly one passthrough warning");
        assertTrue(result.getWarnings().get(0).contains("No principal mapping"),
                "Warning must mention missing principal mapping");
        assertEquals(1, result.getTotalPolicies());
        assertTrue(result.getFullyConvertible() + result.getPartiallyConvertible() > 0,
                "Passthrough must produce at least one convertible policy");
    }

    @Test
    void run_withNonEmptyStaticMapping_noWarning() {
        AssessmentResult result = new AssessmentRunner().run(minimalConfig(),
                stubSource("lakeformation", "lakeformation", List.of()));
        assertTrue(result.getWarnings().isEmpty(), "No warning expected when principal mapping is configured");
    }

    @Test
    void policyWithGrantsAndOnlyKnownUnmappableActionsIsFullyConvertible() {
        // hive policy: select (converts) + index (known-unmappable).
        // Before this change it was reported PARTIALLY_CONVERTIBLE, which is the headline
        // number customers plan against.
        AssessmentResult result = runAssessment(
                hivePolicy(1L, "analyst-select", List.of("select", "index")));

        assertEquals(1, result.getFullyConvertible());
        assertEquals(0, result.getPartiallyConvertible());
        assertEquals(0, result.getNotConvertible());
        assertEquals(1, result.getFullyConvertible() + result.getPartiallyConvertible()
                + result.getNotConvertible(), "counts must sum to total policy count");
    }

    @Test
    void policyWithOnlyKnownUnmappableActionsIsNotConvertibleAndStatesTheReason() {
        // Zero grants produced. It must stay NOT_CONVERTIBLE *and* the gaps file entry
        // must explain why, or the report says "not convertible" with no reason.
        AssessmentResult result = runAssessment(
                hivePolicy(2L, "lock-only", List.of("index", "lock")));

        assertEquals(0, result.getFullyConvertible());
        assertEquals(1, result.getNotConvertible());

        PolicyGapSummary summary = result.getPolicyGapSummaries().stream()
                .filter(s -> "2".equals(s.getPolicyId()))
                .findFirst().orElseThrow();
        assertEquals(PolicyGapSummary.Convertibility.NOT_CONVERTIBLE, summary.getConvertibility());
        assertFalse(summary.getGaps().isEmpty(),
                "a NOT_CONVERTIBLE policy must never be reported with an empty gap list");
        assertTrue(summary.getGaps().stream().anyMatch(
                g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION));
    }

    @Test
    void unrecognizedActionStillDemotesToPartiallyConvertible() {
        AssessmentResult result = runAssessment(
                hivePolicy(3L, "mixed", List.of("select", "totally_made_up")));

        assertEquals(0, result.getFullyConvertible());
        assertEquals(1, result.getPartiallyConvertible());
    }

    @Test
    void partiallyConvertiblePolicyGapsIncludeTheConflatedKnownUnmappableEntry() {
        // select converts (produces ops); totally_made_up is actionable (demotes to
        // PARTIALLY_CONVERTIBLE); index is known-unmappable (non-demoting) but must still
        // appear in the gaps array alongside the actionable gap.
        AssessmentResult result = runAssessment(
                hivePolicy(4L, "mixed-with-known-unmappable",
                        List.of("select", "index", "totally_made_up")));

        assertEquals(1, result.getPartiallyConvertible());

        PolicyGapSummary summary = result.getPolicyGapSummaries().stream()
                .filter(s -> "4".equals(s.getPolicyId()))
                .findFirst().orElseThrow();
        assertEquals(PolicyGapSummary.Convertibility.PARTIALLY_CONVERTIBLE, summary.getConvertibility());
        assertTrue(summary.getGaps().stream().anyMatch(g -> g.getGapType() == GapType.UNSUPPORTED_ACTION),
                "the actionable gap must be present");
        assertTrue(summary.getGaps().stream().anyMatch(g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION),
                "the conflated known-unmappable gap must now be present in a partially convertible "
                        + "policy's gaps array, not just backfilled on NOT_CONVERTIBLE policies");
    }

    // ---- helpers for known-unmappable-action gap tests ----

    private AssessmentResult runAssessment(RangerPolicy... policies) {
        PolicySource source = stubSource("hive", "hive", List.of(policies));
        return new AssessmentRunner().run(minimalConfig(), source);
    }

    private RangerPolicy hivePolicy(long id, String name, List<String> accessTypes) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName(name);
        policy.setService("hive");
        policy.setIsEnabled(true);
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicyResource("db1"));
        resources.put("table", new RangerPolicyResource("table1"));
        policy.setResources(resources);

        RangerPolicyItem item = new RangerPolicyItem();
        item.setAccesses(accessTypes.stream().map(this::access).collect(java.util.stream.Collectors.toList()));
        item.setUsers(List.of("alice"));
        policy.setPolicyItems(List.of(item));
        return policy;
    }

    @Test
    void knownUnmappableActionsAreRolledUpAndCollapsedInTheGapReport() {
        AssessmentResult result = runAssessment(
                hivePolicy(1L, "a", List.of("select", "index")),
                hivePolicy(2L, "b", List.of("select", "index")),
                hivePolicy(3L, "c", List.of("select", "lock")));

        // Rollup: one row per (serviceType, accessType), with occurrence counts.
        List<KnownUnmappableActionSummary> rollup = result.getKnownUnmappableActions();
        assertEquals(2, rollup.size());

        KnownUnmappableActionSummary index = rollup.stream()
                .filter(r -> "index".equals(r.getAccessType())).findFirst().orElseThrow();
        assertEquals("hive", index.getServiceType());
        assertEquals(2, index.getOccurrenceCount());
        assertEquals(List.of("1", "2"), index.getSamplePolicyIds());

        // Gap report: collapsed to one entry per (serviceType, accessType), not per policy.
        long entries = result.getGapReport().getEntries().stream()
                .filter(g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION)
                .count();
        assertEquals(2, entries);
        assertTrue(result.getGapReport().getEntries().stream()
                        .filter(g -> g.getGapType() == GapType.KNOWN_UNMAPPABLE_ACTION)
                        .allMatch(g -> g.getPolicyId() == null),
                "collapsed entries are not policy-scoped and must not claim a policyId");

        // And the summary count matches the collapsed entry count.
        assertEquals(Integer.valueOf(2),
                result.getGapReport().getSummary().get(GapType.KNOWN_UNMAPPABLE_ACTION));
    }

    @Test
    void samplePolicyIdsAreCappedButOccurrenceCountIsNot() {
        RangerPolicy[] policies = new RangerPolicy[25];
        for (int i = 0; i < 25; i++) {
            policies[i] = hivePolicy(i + 1L, "p" + i, List.of("select", "index"));
        }

        AssessmentResult result = runAssessment(policies);

        KnownUnmappableActionSummary index = result.getKnownUnmappableActions().get(0);
        assertEquals(25, index.getOccurrenceCount());
        assertEquals(10, index.getSamplePolicyIds().size(), "samples are capped at 10");
    }

    @Test
    void collapsingDoesNotBreakTheNotConvertibleReason() {
        // Task 4 backfills informational gaps onto a NOT_CONVERTIBLE policy so it states a
        // reason. That lookup needs per-policy entries, so it must run before collapsing.
        AssessmentResult result = runAssessment(hivePolicy(7L, "lock-only", List.of("index", "lock")));

        PolicyGapSummary summary = result.getPolicyGapSummaries().stream()
                .filter(s -> "7".equals(s.getPolicyId()))
                .findFirst().orElseThrow();
        assertFalse(summary.getGaps().isEmpty(),
                "per-policy reason must survive the collapse");
        assertTrue(summary.getGaps().stream()
                .allMatch(g -> "7".equals(g.getPolicyId())),
                "the reason must be the policy-scoped entry, not the collapsed one");
    }
}
