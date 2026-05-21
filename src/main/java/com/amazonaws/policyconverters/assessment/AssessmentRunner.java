package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.app.ConversionServerMain;
import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapReport;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.PassthroughCatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a one-time gap assessment: fetches policies from Ranger Admin,
 * runs the full conversion pipeline in read-only mode, and returns an
 * {@link AssessmentResult} with convertibility counts and the gap report.
 * <p>
 * No Lake Formation API calls are made. The conversion runs with either a real
 * {@link CatalogResolver} (when AWS credentials are provided) or a
 * {@link PassthroughCatalogResolver} (no AWS credentials needed).
 */
public class AssessmentRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AssessmentRunner.class);

    /**
     * Runs the gap assessment against the Ranger Admin described in {@code config}.
     *
     * @param config assessment configuration
     * @return the assessment result with convertibility stats and gap report
     */
    public AssessmentResult run(AssessmentConfig config) {
        List<RangerPolicy> allPolicies = fetchPolicies(config);

        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(config.getPrincipalMapping());
        CatalogResolver catalogResolver = buildCatalogResolver(config);

        Map<String, SourcePolicyAdapter> adapterRegistry = buildAdapterRegistry(config);

        RangerToCedarConverter rangerConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, catalogResolver, gapReporter, schemaProvider);

        CedarToLFConverter lfConverter = new CedarToLFConverter(schemaProvider, gapReporter, null);

        LOG.info("Assessment: converting {} policies", allPolicies.size());
        CedarPolicySet cedarPolicySet = rangerConverter.convert(allPolicies);
        List<LFPermissionOperation> ops = lfConverter.convert(cedarPolicySet);

        GapReport gapReport = gapReporter.getReport();
        int[] counts = computeConvertibilityCounts(allPolicies, ops, gapReport);

        return new AssessmentResult(
                allPolicies.size(),
                counts[0],
                counts[1],
                counts[2],
                ops.size(),
                gapReport);
    }

    protected List<RangerPolicy> fetchPolicies(AssessmentConfig config) {
        List<RangerPolicy> allPolicies = new ArrayList<>();

        if (config.getServices().isEmpty()) {
            // Single-service (legacy) mode: fetch from the default "lakeformation" service
            ServicePolicies sp = ConversionServerMain.fetchPoliciesFromRangerAdmin(
                    config.getRangerAdminUrl(),
                    config.getRangerUsername(),
                    config.getRangerPassword(),
                    "lakeformation");
            if (sp != null && sp.getPolicies() != null) {
                allPolicies.addAll(sp.getPolicies());
            } else {
                LOG.warn("Assessment: no policies returned from Ranger Admin for service 'lakeformation'");
            }
        } else {
            for (RangerServiceConfig svcConfig : config.getServices()) {
                String instanceName = svcConfig.getServiceInstanceName();
                ServicePolicies sp = ConversionServerMain.fetchPoliciesFromRangerAdmin(
                        config.getRangerAdminUrl(),
                        config.getRangerUsername(),
                        config.getRangerPassword(),
                        instanceName);
                if (sp != null && sp.getPolicies() != null) {
                    allPolicies.addAll(sp.getPolicies());
                    LOG.info("Assessment: fetched {} policies from service '{}'",
                            sp.getPolicies().size(), instanceName);
                } else {
                    LOG.warn("Assessment: no policies returned for service '{}'", instanceName);
                }
            }
        }

        LOG.info("Assessment: total {} policies fetched across all services", allPolicies.size());
        return allPolicies;
    }

    private CatalogResolver buildCatalogResolver(AssessmentConfig config) {
        return config.getAwsConfig().map(awsConfig -> {
            AwsCredentialsProvider credentials = ConversionServerMain.buildCredentialsProvider(awsConfig);
            GlueClient glueClient = GlueClient.builder()
                    .region(Region.of(awsConfig.getRegion()))
                    .credentialsProvider(credentials)
                    .build();
            LOG.info("Assessment: using real CatalogResolver with Glue in region {}", awsConfig.getRegion());
            return (CatalogResolver) new CatalogResolver(glueClient);
        }).orElseGet(() -> {
            LOG.info("Assessment: no AWS credentials provided, using PassthroughCatalogResolver");
            return new PassthroughCatalogResolver();
        });
    }

    private Map<String, SourcePolicyAdapter> buildAdapterRegistry(AssessmentConfig config) {
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();

        if (config.getServices().isEmpty()) {
            // Single-service mode: default lakeformation adapter with a no-op AWS context
            AwsContext awsContext = new AwsContext("us-east-1", "000000000000", "000000000000");
            BaseRangerService defaultService = ConversionServerMain.createRangerService(
                    new RangerServiceConfig("lakeformation", "lakeformation", null, null));
            adapterRegistry.put("lakeformation", defaultService.createAdapter(awsContext));
        } else {
            for (RangerServiceConfig svcConfig : config.getServices()) {
                AwsContext awsContext = config.getAwsConfig()
                        .map(aws -> new AwsContext(aws.getRegion(), aws.getCatalogId(), aws.getCatalogId()))
                        .orElse(new AwsContext("us-east-1", "000000000000", "000000000000"));
                BaseRangerService service = ConversionServerMain.createRangerService(svcConfig);
                adapterRegistry.put(svcConfig.getServiceType(), service.createAdapter(awsContext));
            }
        }

        return adapterRegistry;
    }

    /**
     * Correlates policy IDs from gap entries and projected operations to classify
     * each scanned policy as fully convertible, partially convertible, or not convertible.
     *
     * @return int[3]: [fullyConvertible, partiallyConvertible, notConvertible]
     */
    private int[] computeConvertibilityCounts(
            List<RangerPolicy> allPolicies,
            List<LFPermissionOperation> ops,
            GapReport gapReport) {

        // Build set of policy IDs that appear in gap entries
        Set<String> policiesWithGaps = new HashSet<>();
        for (GapEntry entry : gapReport.getEntries()) {
            if (entry.getPolicyId() != null) {
                policiesWithGaps.add(entry.getPolicyId());
            }
        }

        // Build set of policy IDs that produced at least one projected op.
        // sourcePolicyId format is "serviceType:numericId"
        Set<String> policiesWithOps = new HashSet<>();
        for (LFPermissionOperation op : ops) {
            String sourcePolicyId = op.getSourcePolicyId();
            if (sourcePolicyId != null) {
                int colonIdx = sourcePolicyId.indexOf(':');
                String policyId = colonIdx >= 0
                        ? sourcePolicyId.substring(colonIdx + 1)
                        : sourcePolicyId;
                policiesWithOps.add(policyId);
            }
        }

        int fullyConvertible = 0;
        int partiallyConvertible = 0;
        int notConvertible = 0;

        for (RangerPolicy policy : allPolicies) {
            String id = policy.getId() != null ? String.valueOf(policy.getId()) : null;
            boolean hasOps = id != null && policiesWithOps.contains(id);
            boolean hasGaps = id != null && policiesWithGaps.contains(id);

            if (hasOps && !hasGaps) {
                fullyConvertible++;
            } else if (hasOps && hasGaps) {
                partiallyConvertible++;
            } else {
                notConvertible++;
            }
        }

        return new int[]{fullyConvertible, partiallyConvertible, notConvertible};
    }
}
