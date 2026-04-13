package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;

/**
 * Ranger service implementation for the LakeFormation service type.
 *
 * <p>Refactors the existing LakeFormation integration to extend {@link BaseRangerService},
 * reusing the existing {@link RangerServiceAdapter} and bundled service definition.
 */
public class LakeFormationRangerService extends BaseRangerService {

    /**
     * Creates a new LakeFormationRangerService with the given instance name.
     *
     * @param instanceName the Ranger Admin service instance name
     */
    public LakeFormationRangerService(String instanceName) {
        super("lakeformation", instanceName);
    }

    @Override
    public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
        return new RangerServiceAdapter(awsContext);
    }

    @Override
    public String getServiceDefinitionResourcePath() {
        return "/ranger-servicedef-lakeformation.json";
    }
}
