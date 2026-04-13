package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.HiveServiceAdapter;

/**
 * Ranger service implementation for the Apache Hive service type.
 *
 * <p>Extends {@link BaseRangerService} and delegates policy adaptation to
 * {@link HiveServiceAdapter}, which maps Hive access types and resource
 * hierarchy (database, table, column) to Cedar actions and Glue ARN entity
 * references.
 */
public class HiveRangerService extends BaseRangerService {

    /**
     * Creates a new HiveRangerService with the given instance name.
     *
     * @param instanceName the Ranger Admin service instance name
     */
    public HiveRangerService(String instanceName) {
        super("hive", instanceName);
    }

    @Override
    public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
        return new HiveServiceAdapter(awsContext);
    }

    @Override
    public String getServiceDefinitionResourcePath() {
        return "/ranger-servicedef-hive.json";
    }
}
