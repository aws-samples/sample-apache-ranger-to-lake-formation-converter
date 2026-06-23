package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.app.ConversionServerMain;
import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.cedar.CedarToS3AccessGrantsConverter;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.lakeformation.PassthroughPrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapperFactory;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.model.GapReport;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.PassthroughCatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClient;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a one-time gap assessment: loads policies from a {@link PolicySource},
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
     * Runs the gap assessment using the supplied {@code source} to obtain policies.
     *
     * @param config assessment configuration
     * @param source policy source (Ranger Admin, export file, etc.)
     * @return the assessment result with convertibility stats and gap report
     */
    public AssessmentResult run(AssessmentConfig config, PolicySource source) {
        List<ServicePolicyBatch> batches = source.load();

        // Build adapter registry keyed on serviceName (instance name) to match
        // RangerToCedarConverter's lookup which uses policy.getService()
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        for (ServicePolicyBatch batch : batches) {
            if (!batch.isSkipped()) {
                AwsContext awsContext = config.getAwsConfig()
                        .map(aws -> new AwsContext(aws.getRegion(), aws.getCatalogId(), aws.getCatalogId()))
                        .orElse(new AwsContext("us-east-1", "000000000000", "000000000000"));
                BaseRangerService service = ConversionServerMain.createRangerService(
                        new RangerServiceConfig(batch.getServiceType(), batch.getServiceName(), null, null));
                adapterRegistry.put(batch.getServiceName(), service.createAdapter(awsContext));
            }
        }

        GapReporter gapReporter = new GapReporter();

        // Collect assessed policies; record gap entries for skipped services
        List<RangerPolicy> allPolicies = new ArrayList<>();
        List<AssessedService> assessedServices = new ArrayList<>();
        for (ServicePolicyBatch batch : batches) {
            if (batch.isSkipped()) {
                gapReporter.recordGap(new GapEntry(
                        null, null,
                        GapEntry.GapType.UNSUPPORTED_SERVICE_TYPE,
                        null,
                        "Service '" + batch.getServiceName() + "' (serviceType='" + batch.getServiceType()
                                + "') has no registered adapter. All " + batch.getRawPolicyCount()
                                + " policies in this service are skipped.",
                        "Supported service types are: lakeformation, hive, presto, trino, amazon-emr-emrfs."));
                assessedServices.add(AssessedService.skipped(
                        batch.getServiceName(), batch.getServiceType(), batch.getSkipReason()));
            } else {
                allPolicies.addAll(batch.getPolicies());
                assessedServices.add(AssessedService.assessed(
                        batch.getServiceName(), batch.getServiceType(), batch.getPolicies().size()));
            }
        }

        CedarSchemaProvider schemaProvider = new CedarSchemaProvider();

        List<String> warnings = new ArrayList<>();
        // Build PrincipalMapper — use passthrough when no mapping is configured
        IdentitystoreClient identityStoreClient = null;
        PrincipalMappingConfig principalMappingConfig = config.getPrincipalMapping();
        PrincipalMapper principalMapper;
        if (isDefaultEmptyMapping(principalMappingConfig)) {
            principalMapper = new PassthroughPrincipalMapper();
            String warning = "No principal mapping configured. Ranger usernames are passed through as-is "
                    + "(e.g. \"ranger-user:alice\", \"ranger-group:analysts\"). Re-run with a config file "
                    + "that includes a principalMapping section to produce accurate LF grant output.";
            warnings.add(warning);
            LOG.warn(warning);
        } else {
            if (principalMappingConfig != null
                    && principalMappingConfig.getType() == PrincipalMapperType.IDENTITY_CENTER) {
                identityStoreClient = config.getAwsConfig().map(awsConfig -> {
                    software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials =
                            ConversionServerMain.buildCredentialsProvider(awsConfig);
                    return IdentitystoreClient.builder()
                            .region(Region.of(principalMappingConfig.getIdcConfig().getRegion()))
                            .credentialsProvider(credentials)
                            .build();
                }).orElse(null);
            }
            principalMapper = PrincipalMapperFactory.create(principalMappingConfig, identityStoreClient, null);
        }
        CatalogResolver catalogResolver = buildCatalogResolver(config);

        RangerToCedarConverter rangerConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, catalogResolver, gapReporter, schemaProvider);
        rangerConverter.setSkipCedarValidation(config.isSkipCedarValidation());

        CedarToLFConverter lfConverter = new CedarToLFConverter(schemaProvider, gapReporter, null);

        LOG.info("Assessment: converting {} policies", allPolicies.size());
        CedarPolicySet cedarPolicySet = rangerConverter.convert(allPolicies);
        List<LFPermissionOperation> ops = lfConverter.convert(cedarPolicySet);

        // S3 Access Grants gap assessment
        List<S3AccessGrantOperation> s3AgOps = convertToS3AgOps(cedarPolicySet);

        if (!s3AgOps.isEmpty()) {
            if (config.getS3AccessGrants() != null) {
                // Check location coverage
                S3AccessGrantsClient s3agClient = createS3AccessGrantsClient(config.getS3AccessGrants());
                Set<String> registeredLocations = s3agClient.listRegisteredLocations();
                for (S3AccessGrantOperation op : s3AgOps) {
                    boolean covered = registeredLocations.stream()
                            .anyMatch(loc -> op.s3Prefix().startsWith(loc));
                    if (!covered) {
                        gapReporter.recordGap(new GapEntry(
                                null,
                                null,
                                GapType.UNREGISTERED_S3_LOCATION,
                                op.s3Prefix(),
                                "Prefix " + op.s3Prefix() + " is not covered by any registered S3 Access Grants location",
                                null));
                    }
                }
            } else {
                // No S3AG config — cannot validate
                for (S3AccessGrantOperation op : s3AgOps) {
                    gapReporter.recordGap(new GapEntry(
                            null,
                            null,
                            GapType.CANNOT_VALIDATE_S3_LOCATION,
                            op.s3Prefix(),
                            "No s3AccessGrants config provided; cannot check location registration for " + op.s3Prefix(),
                            null));
                }
            }
        }

        GapReport gapReport = gapReporter.getReport();
        ConvertibilityResult convertibility =
                computeConvertibility(allPolicies, ops, s3AgOps, gapReport);

        return new AssessmentResult(
                allPolicies.size(),
                convertibility.fullyConvertible,
                convertibility.partiallyConvertible,
                convertibility.notConvertible,
                ops.size(),
                gapReport,
                source.sourceLabel(),
                assessedServices,
                warnings,
                ops,
                convertibility.policyGapSummaries);
    }

    /**
     * Converts the Cedar PolicySet to S3 Access Grants operations.
     * Overridable in tests to inject pre-built operations without the full Ranger pipeline.
     */
    protected List<S3AccessGrantOperation> convertToS3AgOps(CedarPolicySet cedarPolicySet) {
        return new CedarToS3AccessGrantsConverter().convert(cedarPolicySet);
    }

    /**
     * Factory method for {@link S3AccessGrantsClient}. Overridable in tests to inject a mock.
     */
    protected S3AccessGrantsClient createS3AccessGrantsClient(
            com.amazonaws.policyconverters.config.S3AccessGrantsConfig s3Config) {
        return new S3AccessGrantsClient(s3Config, null /* no dead-letter in assessment */);
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

    private static boolean isDefaultEmptyMapping(PrincipalMappingConfig cfg) {
        return cfg != null
                && cfg.getType() == PrincipalMapperType.STATIC
                && cfg.getUserMappings().isEmpty()
                && cfg.getGroupMappings().isEmpty()
                && cfg.getRoleMappings().isEmpty()
                && cfg.getDelegates().isEmpty();
    }

    // Gap types that are informational only and excluded from the gaps output file.
    private static final Set<GapEntry.GapType> INFORMATIONAL_GAP_TYPES = new HashSet<>(Arrays.asList(
            GapEntry.GapType.CANNOT_VALIDATE_S3_LOCATION,
            GapEntry.GapType.WILDCARD_PATTERN
    ));

    private static final class ConvertibilityResult {
        final int fullyConvertible;
        final int partiallyConvertible;
        final int notConvertible;
        final List<PolicyGapSummary> policyGapSummaries;

        ConvertibilityResult(int fullyConvertible, int partiallyConvertible, int notConvertible,
                             List<PolicyGapSummary> policyGapSummaries) {
            this.fullyConvertible = fullyConvertible;
            this.partiallyConvertible = partiallyConvertible;
            this.notConvertible = notConvertible;
            this.policyGapSummaries = policyGapSummaries;
        }
    }

    /**
     * Classifies each scanned policy as fully convertible, partially convertible, or not
     * convertible, and builds per-policy gap summaries for actionable gaps.
     */
    private ConvertibilityResult computeConvertibility(
            List<RangerPolicy> allPolicies,
            List<LFPermissionOperation> ops,
            List<S3AccessGrantOperation> s3AgOps,
            GapReport gapReport) {

        // Group actionable gap entries by policy ID (excluding informational gap types)
        Map<String, List<GapEntry>> actionableGapsByPolicy = new LinkedHashMap<>();
        for (GapEntry entry : gapReport.getEntries()) {
            if (entry.getPolicyId() != null && !INFORMATIONAL_GAP_TYPES.contains(entry.getGapType())) {
                actionableGapsByPolicy
                        .computeIfAbsent(entry.getPolicyId(), k -> new ArrayList<>())
                        .add(entry);
            }
        }

        // Build set of policy IDs that produced at least one projected op.
        // sourcePolicyId format is "serviceType:numericId"
        Set<String> policiesWithOps = new HashSet<>();
        for (LFPermissionOperation op : ops) {
            String sourcePolicyId = op.getSourcePolicyId();
            if (sourcePolicyId != null) {
                int colonIdx = sourcePolicyId.indexOf(':');
                policiesWithOps.add(colonIdx >= 0
                        ? sourcePolicyId.substring(colonIdx + 1) : sourcePolicyId);
            }
        }
        for (S3AccessGrantOperation op : s3AgOps) {
            String sourcePolicyId = op.sourcePolicyId();
            if (sourcePolicyId != null) {
                int colonIdx = sourcePolicyId.indexOf(':');
                policiesWithOps.add(colonIdx >= 0
                        ? sourcePolicyId.substring(colonIdx + 1) : sourcePolicyId);
            }
        }

        int fullyConvertible = 0;
        int partiallyConvertible = 0;
        int notConvertible = 0;
        List<PolicyGapSummary> summaries = new ArrayList<>();

        for (RangerPolicy policy : allPolicies) {
            String id = policy.getId() != null ? String.valueOf(policy.getId()) : null;
            boolean hasOps = id != null && policiesWithOps.contains(id);
            List<GapEntry> gaps = id != null
                    ? actionableGapsByPolicy.getOrDefault(id, Collections.emptyList())
                    : Collections.emptyList();
            boolean hasActionableGaps = !gaps.isEmpty();

            PolicyGapSummary.Convertibility convertibility;
            if (hasOps && !hasActionableGaps) {
                fullyConvertible++;
                convertibility = null; // fully convertible — not included in gap file
            } else if (hasOps) {
                partiallyConvertible++;
                convertibility = PolicyGapSummary.Convertibility.PARTIALLY_CONVERTIBLE;
            } else {
                notConvertible++;
                convertibility = PolicyGapSummary.Convertibility.NOT_CONVERTIBLE;
            }

            if (convertibility != null) {
                summaries.add(new PolicyGapSummary(id, policy.getName(), convertibility, gaps));
            }
        }

        return new ConvertibilityResult(fullyConvertible, partiallyConvertible, notConvertible, summaries);
    }
}
