package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.PrestoServiceAdapter;

/**
 * Ranger service implementation for the Presto service type.
 *
 * <p>Extends {@link BaseRangerService} and delegates policy adaptation to
 * {@link PrestoServiceAdapter}, which maps Presto access types and resource
 * hierarchy (catalog, schema, table, column) to Cedar actions and Glue ARN
 * entity references. Only policies whose catalog matches the configured
 * {@code gdcCatalogName} are processed.
 */
public class PrestoRangerService extends BaseRangerService {

    private final String gdcCatalogName;

    /**
     * Creates a new PrestoRangerService with the given instance name and GDC catalog name.
     *
     * @param instanceName   the Ranger Admin service instance name
     * @param gdcCatalogName the Glue Data Catalog name used for catalog filtering
     */
    public PrestoRangerService(String instanceName, String gdcCatalogName) {
        super("presto", instanceName);
        this.gdcCatalogName = gdcCatalogName;
    }

    @Override
    public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
        return new PrestoServiceAdapter(awsContext, gdcCatalogName);
    }

    @Override
    public String getServiceDefinitionResourcePath() {
        return "/ranger-servicedef-presto.json";
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
