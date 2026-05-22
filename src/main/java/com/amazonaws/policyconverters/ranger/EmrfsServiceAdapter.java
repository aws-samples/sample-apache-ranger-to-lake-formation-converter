package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SourcePolicyAdapter} for the EMRFS S3 Ranger service definition.
 *
 * <p>Maps EMRFS S3 access types to Cedar action name strings and produces
 * S3 ARN-formatted entity identifiers for the {@code sthreeresource} resource level.
 */
public class EmrfsServiceAdapter implements SourcePolicyAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(EmrfsServiceAdapter.class);

    private static final String SERVICE_TYPE = "amazon-emr-emrfs";
    private static final String RESOURCE_KEY = "sthreeresource";
    private static final String ENTITY_TYPE = "S3::Object";

    private final AwsContext awsContext;

    public EmrfsServiceAdapter(AwsContext awsContext) {
        this.awsContext = awsContext;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public Set<String> mapAccessTypeToCedarActions(String sourceAccessType) {
        if (sourceAccessType == null || sourceAccessType.trim().isEmpty()) {
            LOG.error("Null or empty access type provided");
            return Collections.emptySet();
        }
        switch (sourceAccessType.trim()) {
            case "GetObject":
                return Set.of("s3:GetObject");
            case "PutObject":
                return Set.of("s3:PutObject");
            case "ListObjects":
                return Set.of("s3:ListObjects");
            case "DeleteObject":
                return Set.of("s3:DeleteObject");
            default:
                LOG.error("Unknown EMRFS access type: '{}' — this access type will be skipped, "
                        + "affected policy items may lose permissions", sourceAccessType);
                return Collections.emptySet();
        }
    }

    @Override
    public CedarEntityRef buildEntityRef(RangerPolicy policy, String resourceLevel) {
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || !resources.containsKey(RESOURCE_KEY)) {
            LOG.warn("Policy {} has no '{}' resource key; skipping entity ref construction",
                    policy.getId(), RESOURCE_KEY);
            return null;
        }
        RangerPolicyResource resource = resources.get(RESOURCE_KEY);
        List<String> values = resource.getValues();
        if (values == null || values.isEmpty()) {
            LOG.warn("Policy {} has empty values for '{}'; skipping entity ref construction",
                    policy.getId(), RESOURCE_KEY);
            return null;
        }
        String value = values.get(0);
        String arn = buildS3Arn(value, resource.getIsRecursive());
        return new CedarEntityRef(ENTITY_TYPE, arn);
    }

    /**
     * Build a list of {@link CedarEntityRef} instances from explicit resource values.
     * Useful when the converter iterates over expanded resource combinations.
     *
     * @param resourceLevel the resource level (expected: "sthreeresource")
     * @param values        the list of S3 resource values in {@code bucket/prefix} format
     * @param isRecursive   whether the resource is recursive (appends {@code /*} to the ARN)
     * @return list of constructed {@link CedarEntityRef} instances, empty if values is null/empty
     */
    public List<CedarEntityRef> buildEntityRefFromValues(String resourceLevel,
                                                         List<String> values,
                                                         boolean isRecursive) {
        if (values == null || values.isEmpty()) {
            LOG.warn("No values provided for resource level '{}'; returning empty list", resourceLevel);
            return Collections.emptyList();
        }
        if (!RESOURCE_KEY.equals(resourceLevel)) {
            LOG.warn("Unexpected resource level '{}' for EMRFS adapter; expected '{}'",
                    resourceLevel, RESOURCE_KEY);
        }
        List<CedarEntityRef> refs = new ArrayList<>(values.size());
        for (String value : values) {
            String arn = buildS3Arn(value, isRecursive);
            refs.add(new CedarEntityRef(ENTITY_TYPE, arn));
        }
        return refs;
    }

    @Override
    public String buildPrincipalRef(String resolvedPrincipalId) {
        return resolvedPrincipalId;
    }

    @Override
    public Optional<AwsContext> getAwsContext() {
        return Optional.of(awsContext);
    }

    private String buildS3Arn(String value, Boolean isRecursive) {
        String arn = "arn:aws:s3:::" + value;
        if (Boolean.TRUE.equals(isRecursive)) {
            arn = arn + "/*";
        }
        return arn;
    }
}
