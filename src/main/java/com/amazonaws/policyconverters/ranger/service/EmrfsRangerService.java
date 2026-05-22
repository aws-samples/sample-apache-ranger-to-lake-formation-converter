package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.EmrfsServiceAdapter;

/**
 * Ranger service implementation for the EMRFS S3 service type.
 *
 * <p>Wires the EMRFS Ranger plugin into the Cedar conversion pipeline by extending
 * {@link BaseRangerService} and providing the {@link EmrfsServiceAdapter}.
 */
public class EmrfsRangerService extends BaseRangerService {

    /**
     * Creates a new EmrfsRangerService with the given instance name.
     *
     * @param instanceName the Ranger Admin service instance name
     */
    public EmrfsRangerService(String instanceName) {
        super("amazon-emr-emrfs", instanceName);
    }

    @Override
    public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
        return new EmrfsServiceAdapter(awsContext);
    }

    @Override
    public String getServiceDefinitionResourcePath() {
        return "/ranger-servicedef-amazon-emr-emrfs.json";
    }
}
