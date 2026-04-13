package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.TrinoServiceAdapter;

/**
 * Ranger service implementation for the Trino service type.
 *
 * <p>Extends {@link BaseRangerService} and delegates policy adaptation to
 * {@link TrinoServiceAdapter}, which maps Trino access types and resource
 * hierarchy (catalog, schema, table, column) to Cedar actions and Glue ARN
 * entity references. Only policies whose catalog matches the configured
 * {@code gdcCatalogName} are processed.
 */
public class TrinoRangerService extends BaseRangerService {

    private final String gdcCatalogName;

    /**
     * Creates a new TrinoRangerService with the given instance name and GDC catalog name.
     *
     * @param instanceName   the Ranger Admin service instance name
     * @param gdcCatalogName the Glue Data Catalog name used for catalog filtering
     */
    public TrinoRangerService(String instanceName, String gdcCatalogName) {
        super("trino", instanceName);
        this.gdcCatalogName = gdcCatalogName;
    }

    @Override
    public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
        return new TrinoServiceAdapter(awsContext, gdcCatalogName);
    }

    @Override
    public String getServiceDefinitionResourcePath() {
        return "/ranger-servicedef-trino.json";
    }

    /**
     * Returns the configured GDC catalog name.
     *
     * @return the Glue Data Catalog name
     */
    public String getGdcCatalogName() {
        return gdcCatalogName;
    }
}
