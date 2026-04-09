package com.amazonaws.policyconverters.cedar;

import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.apache.ranger.plugin.model.RangerPolicy;

import java.util.Optional;
import java.util.Set;

/**
 * Maps a specific policy source's access types and resource hierarchy
 * into Cedar actions and DataCatalog:: entity types.
 *
 * <p>Each implementation handles a single service type (e.g., "lakeformation", "trino").
 * The {@link com.amazonaws.policyconverters.ranger.RangerToCedarConverter} delegates
 * service-type-specific mapping to the adapter registered for the incoming policy's service type.
 */
public interface SourcePolicyAdapter {

    /**
     * The service type this adapter handles (e.g., "lakeformation", "trino").
     */
    String getServiceType();

    /**
     * Map a source access type to one or more Cedar action names.
     * Returns empty set if the access type is not mappable.
     */
    Set<String> mapAccessTypeToCedarActions(String sourceAccessType);

    /**
     * Build a Cedar entity identifier for the given resource.
     * Returns the entity type (e.g., "DataCatalog::Database") and
     * the identifier string (e.g., an ARN or URN).
     */
    CedarEntityRef buildEntityRef(RangerPolicy policy, String resourceLevel);

    /**
     * Build a Cedar principal identifier from a resolved IAM ARN
     * or other identity string.
     */
    String buildPrincipalRef(String resolvedPrincipalId);

    /**
     * Return the AWS region and account ID for ARN construction,
     * or empty if not applicable (non-AWS sources).
     */
    Optional<AwsContext> getAwsContext();
}
