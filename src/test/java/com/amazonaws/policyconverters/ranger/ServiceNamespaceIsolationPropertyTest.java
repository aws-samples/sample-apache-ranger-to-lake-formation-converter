package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.StaticPrincipalMapper;
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

// Feature: multi-ranger-plugin-support, Property 9: Service Namespace Isolation

/**
 * Property-based test verifying that adding Cedar policies from service type B
 * to a set of Cedar policies from service type A does NOT cause any revocations
 * of service A's policies in the computed diff.
 *
 * <p>Because @source annotations are prefixed with service type (e.g.,
 * @source("lakeformation:42"), @source("hive:7")), policies from different
 * services have different IDs and won't conflict.
 *
 * **Validates: Requirements 9.1, 9.2**
 */
@Tag("multi-ranger-plugin-support")
class ServiceNamespaceIsolationPropertyTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");

    // -----------------------------------------------------------------------
    // Property 9a: Cedar String Level — Merged set contains all of A's statements
    // Adding service B policies does not remove any of service A's policies.
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void mergedCedarSetContainsAllServiceAPoliciesUnchanged(
            @ForAll("lakeformationPolicies") List<RangerPolicy> serviceAPolicies,
            @ForAll("hivePolicies") List<RangerPolicy> serviceBPolicies
    ) {
        // Convert service A policies alone
        RangerToCedarConverter converterA = buildConverter("lakeformation");
        CedarPolicySet cedarA = converterA.convert(serviceAPolicies);

        // Convert service A + service B policies together
        List<RangerPolicy> mergedPolicies = new ArrayList<>(serviceAPolicies);
        mergedPolicies.addAll(serviceBPolicies);

        RangerToCedarConverter converterMerged = buildConverter("lakeformation", "hive");
        CedarPolicySet cedarMerged = converterMerged.convert(mergedPolicies);

        // All of service A's source policy IDs must appear in the merged set
        List<String> sourceIdsA = cedarA.getSourcePolicyIds();
        List<String> sourceIdsMerged = cedarMerged.getSourcePolicyIds();

        for (String sourceId : sourceIdsA) {
            assertTrue(sourceIdsMerged.contains(sourceId),
                    "Merged set should contain service A's policy with source ID: " + sourceId);
        }

        // The merged set should have at least as many policies as service A alone
        assertTrue(cedarMerged.getPermitCount() >= cedarA.getPermitCount(),
                "Merged permit count (" + cedarMerged.getPermitCount()
                        + ") should be >= service A permit count (" + cedarA.getPermitCount() + ")");
    }

    // -----------------------------------------------------------------------
    // Property 9b: LF Operation Diff Level — No revocations of A's permissions
    // When baseline is service A only and merged is A+B, the diff should
    // contain zero revocations and all of A's operations should be unchanged.
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void addingServiceBPoliciesCausesNoRevocationsOfServiceA(
            @ForAll("lakeformationPolicies") List<RangerPolicy> serviceAPolicies,
            @ForAll("hivePolicies") List<RangerPolicy> serviceBPolicies
    ) {
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        // Convert service A policies to Cedar, then to LF operations (baseline)
        RangerToCedarConverter converterA = buildConverter("lakeformation");
        CedarPolicySet cedarA = converterA.convert(serviceAPolicies);
        GapReporter gapReporterLfA = new GapReporter();
        CedarToLFConverter cedarToLfA = new CedarToLFConverter(schemaProvider, gapReporterLfA, null);
        List<LFPermissionOperation> baselineOps = cedarToLfA.convert(cedarA);

        // Convert merged (A + B) policies to Cedar, then to LF operations
        List<RangerPolicy> mergedPolicies = new ArrayList<>(serviceAPolicies);
        mergedPolicies.addAll(serviceBPolicies);

        RangerToCedarConverter converterMerged = buildConverter("lakeformation", "hive");
        CedarPolicySet cedarMerged = converterMerged.convert(mergedPolicies);
        GapReporter gapReporterLfMerged = new GapReporter();
        CedarToLFConverter cedarToLfMerged = new CedarToLFConverter(schemaProvider, gapReporterLfMerged, null);
        List<LFPermissionOperation> mergedOps = cedarToLfMerged.convert(cedarMerged);

        // Build permission identity sets (principalArn + resource + permissions + grantable)
        // to compute diff — same logic as SyncService.computeDiff
        Set<PermissionIdentity> baselineKeys = toIdentitySet(baselineOps);
        Set<PermissionIdentity> mergedKeys = toIdentitySet(mergedOps);

        // Find revocations: in baseline but not in merged
        Set<PermissionIdentity> revocations = new HashSet<>(baselineKeys);
        revocations.removeAll(mergedKeys);

        assertTrue(revocations.isEmpty(),
                "Adding service B policies should not revoke any service A permissions, "
                        + "but got " + revocations.size() + " revocations: " + revocations);

        // All baseline operations should still be present in merged
        for (PermissionIdentity key : baselineKeys) {
            assertTrue(mergedKeys.contains(key),
                    "Baseline permission should be present in merged set: " + key);
        }
    }

    // -----------------------------------------------------------------------
    // Property 9c: Source annotations are properly namespaced with service type
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void sourceAnnotationsArePrefixedWithServiceType(
            @ForAll("lakeformationPolicies") List<RangerPolicy> serviceAPolicies,
            @ForAll("hivePolicies") List<RangerPolicy> serviceBPolicies
    ) {
        List<RangerPolicy> mergedPolicies = new ArrayList<>(serviceAPolicies);
        mergedPolicies.addAll(serviceBPolicies);

        RangerToCedarConverter converter = buildConverter("lakeformation", "hive");
        CedarPolicySet cedarMerged = converter.convert(mergedPolicies);

        List<String> sourceIds = cedarMerged.getSourcePolicyIds();

        for (String sourceId : sourceIds) {
            assertTrue(sourceId.contains(":"),
                    "Source ID should be prefixed with service type (contain ':'): " + sourceId);
            String prefix = sourceId.substring(0, sourceId.indexOf(':'));
            assertTrue(prefix.equals("lakeformation") || prefix.equals("hive"),
                    "Source ID prefix should be 'lakeformation' or 'hive', got: " + prefix);
        }
    }

    // -----------------------------------------------------------------------
    // Permission identity for diff comparison (mirrors SyncService.PermissionKey)
    // -----------------------------------------------------------------------

    private static final class PermissionIdentity {
        private final String principalArn;
        private final LFResource resource;
        private final Set<LFPermission> permissions;
        private final boolean grantable;

        PermissionIdentity(LFPermissionOperation op) {
            this.principalArn = op.getPrincipalArn();
            this.resource = op.getResource();
            this.permissions = op.getPermissions();
            this.grantable = op.isGrantable();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PermissionIdentity that = (PermissionIdentity) o;
            return grantable == that.grantable
                    && Objects.equals(principalArn, that.principalArn)
                    && Objects.equals(resource, that.resource)
                    && Objects.equals(permissions, that.permissions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(principalArn, resource, permissions, grantable);
        }

        @Override
        public String toString() {
            return "PermissionIdentity{principal=" + principalArn
                    + ", resource=" + resource
                    + ", permissions=" + permissions
                    + ", grantable=" + grantable + "}";
        }
    }

    private static Set<PermissionIdentity> toIdentitySet(List<LFPermissionOperation> ops) {
        Set<PermissionIdentity> keys = new HashSet<>();
        for (LFPermissionOperation op : ops) {
            keys.add(new PermissionIdentity(op));
        }
        return keys;
    }

    // -----------------------------------------------------------------------
    // Converter factory
    // -----------------------------------------------------------------------

    private RangerToCedarConverter buildConverter(String... serviceTypes) {
        Map<String, SourcePolicyAdapter> registry = new HashMap<>();
        for (String serviceType : serviceTypes) {
            switch (serviceType) {
                case "lakeformation":
                    registry.put("lakeformation", new RangerServiceAdapter(AWS_CONTEXT));
                    break;
                case "hive":
                    registry.put("hive", new HiveServiceAdapter(AWS_CONTEXT));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown service type: " + serviceType);
            }
        }

        Map<String, String> userMap = new HashMap<>();
        userMap.put("alice", "arn:aws:iam::123456789012:user/alice");
        userMap.put("bob", "arn:aws:iam::123456789012:user/bob");
        userMap.put("charlie", "arn:aws:iam::123456789012:user/charlie");
        PrincipalMapper principalMapper = StaticPrincipalMapper.fromConfig(
                new PrincipalMappingConfig(userMap, Collections.emptyMap(), Collections.emptyMap()), null);

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
        return lakeformationPolicy().list().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<List<RangerPolicy>> hivePolicies() {
        return hivePolicy().list().ofMinSize(1).ofMaxSize(3);
    }

    private Arbitrary<RangerPolicy> lakeformationPolicy() {
        Arbitrary<Long> policyIds = Arbitraries.longs().between(1, 500);
        Arbitrary<String> databases = Arbitraries.of("sales_db", "analytics_db", "finance_db");
        Arbitrary<String> tables = Arbitraries.of("orders", "customers", "transactions");
        Arbitrary<String> users = Arbitraries.of("alice", "bob", "charlie");
        Arbitrary<String> accessTypes = Arbitraries.of("select", "alter", "drop");

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

    private Arbitrary<RangerPolicy> hivePolicy() {
        Arbitrary<Long> policyIds = Arbitraries.longs().between(1000, 1500);
        Arbitrary<String> databases = Arbitraries.of("warehouse_db", "staging_db", "raw_db");
        Arbitrary<String> tables = Arbitraries.of("events", "logs", "metrics");
        Arbitrary<String> users = Arbitraries.of("alice", "bob", "charlie");
        Arbitrary<String> accessTypes = Arbitraries.of("select", "alter", "drop");

        return Combinators.combine(policyIds, databases, tables, users, accessTypes)
                .as((id, db, table, user, accessType) -> {
                    RangerPolicy policy = new RangerPolicy();
                    policy.setId(id);
                    policy.setName("hive_policy_" + id);
                    policy.setService("hive");
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
