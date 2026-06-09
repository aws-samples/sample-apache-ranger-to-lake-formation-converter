package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.app.ConversionServerMain;
import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.cedar.CedarToS3AccessGrantsConverter;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
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
import java.util.HashMap;
import java.util.HashSet;
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

        // Build IdentitystoreClient only when needed
        IdentitystoreClient identityStoreClient = null;
        com.amazonaws.policyconverters.config.PrincipalMappingConfig principalMappingConfig =
                config.getPrincipalMapping();
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
        PrincipalMapper principalMapper = PrincipalMapperFactory.create(
                principalMappingConfig, identityStoreClient, null);
        CatalogResolver catalogResolver = buildCatalogResolver(config);

        RangerToCedarConverter rangerConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, catalogResolver, gapReporter, schemaProvider);

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
        int[] counts = computeConvertibilityCounts(allPolicies, ops, gapReport);

        return new AssessmentResult(
                allPolicies.size(),
                counts[0],
                counts[1],
                counts[2],
                ops.size(),
                gapReport,
                source.sourceLabel(),
                assessedServices);
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
