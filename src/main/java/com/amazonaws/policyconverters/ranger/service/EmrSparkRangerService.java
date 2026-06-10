package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.EmrSparkServiceAdapter;

/**
 * Ranger service implementation for the Amazon EMR Spark service type.
 *
 * <p>Wires the EMR Spark Ranger plugin into the Cedar conversion pipeline by extending
 * {@link BaseRangerService} and providing the {@link EmrSparkServiceAdapter}.
 */
public class EmrSparkRangerService extends BaseRangerService {

    /**
     * Creates a new EmrSparkRangerService with the given instance name.
     *
     * @param instanceName the Ranger Admin service instance name
     */
    public EmrSparkRangerService(String instanceName) {
        super("amazon-emr-spark", instanceName);
    }

    @Override
    public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
        return new EmrSparkServiceAdapter(awsContext);
    }

    @Override
    public String getServiceDefinitionResourcePath() {
        return "/ranger-servicedef-amazon-emr-spark.json";
    }
}
