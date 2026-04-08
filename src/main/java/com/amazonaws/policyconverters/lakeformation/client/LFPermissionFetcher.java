package com.amazonaws.policyconverters.lakeformation.client;

import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.PermissionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lakeformation.model.AccessDeniedException;
import software.amazon.awssdk.services.lakeformation.model.DataLocationResource;
import software.amazon.awssdk.services.lakeformation.model.DatabaseResource;
import software.amazon.awssdk.services.lakeformation.model.LakeFormationException;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.Permission;
import software.amazon.awssdk.services.lakeformation.model.PrincipalResourcePermissions;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.TableResource;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches LakeFormation permissions via the ListPermissions API,
 * paginates through all pages, normalizes SDK responses into
 * {@link LFPermissionOperation} objects, and applies filtering.
 */
public class LFPermissionFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(LFPermissionFetcher.class);

    private final software.amazon.awssdk.services.lakeformation.LakeFormationClient lfClient;

    public LFPermissionFetcher(
            software.amazon.awssdk.services.lakeformation.LakeFormationClient lfClient) {
        this.lfClient = lfClient;
    }

    /**
     * Fetch all LakeFormation permissions for the given catalog.
     * Paginates through all pages using NextToken.
     * Retries transient errors with exponential backoff.
     *
     * @param catalogId the Glue Data Catalog ID
     * @param filter    optional filter for scoped retrieval (may be null)
     * @return list of normalized LFPermissionOperation objects (all with OperationType.GRANT)
     * @throws LakeFormationClientException if retrieval fails after retries
     */
    public List<LFPermissionOperation> fetchPermissions(String catalogId, PermissionFilter filter)
            throws LakeFormationClientException {
        List<LFPermissionOperation> results = new ArrayList<>();
        String nextToken = null;

        do {
            ListPermissionsRequest.Builder requestBuilder = ListPermissionsRequest.builder()
                    .catalogId(catalogId);
            if (nextToken != null) {
                requestBuilder.nextToken(nextToken);
            }

            ListPermissionsResponse response = callListPermissions(requestBuilder.build());

            for (PrincipalResourcePermissions entry : response.principalResourcePermissions()) {
                List<LFPermissionOperation> normalized = normalizeEntry(entry);
                for (LFPermissionOperation op : normalized) {
                    if (filter == null || !filter.shouldExclude(op)) {
                        results.add(op);
                    }
                }
            }

            nextToken = response.nextToken();
        } while (nextToken != null);

        LOG.info("Fetched {} permissions for catalogId={}", results.size(), catalogId);
        return results;
    }

    /**
     * Call ListPermissions, wrapping SDK exceptions into LakeFormationClientException.
     * Transient error retries are handled by the AWS SDK client's built-in retry policy.
     */
    private ListPermissionsResponse callListPermissions(ListPermissionsRequest request)
            throws LakeFormationClientException {
        try {
            return lfClient.listPermissions(request);
        } catch (AccessDeniedException e) {
            throw new LakeFormationClientException(
                    "Access denied: insufficient IAM permissions for lakeformation:ListPermissions", e);
        } catch (LakeFormationException e) {
            throw new LakeFormationClientException(
                    "ListPermissions failed: " + e.getMessage(), e);
        }
    }

    /**
     * Normalize a single PrincipalResourcePermissions SDK entry into
     * zero or more LFPermissionOperation objects.
     * Returns empty list if the entry has an unrecognized resource type or permission.
     */
    List<LFPermissionOperation> normalizeEntry(PrincipalResourcePermissions entry) {
        List<LFPermissionOperation> operations = new ArrayList<>();

        String principalArn = entry.principal().dataLakePrincipalIdentifier();
        Resource sdkResource = entry.resource();

        LFResource resource = reverseMapResource(sdkResource);
        if (resource == null) {
            LOG.warn("Skipping entry with unrecognized resource type: principal={}, resource={}",
                    principalArn, sdkResource);
            return operations;
        }

        // Handle regular permissions
        if (entry.permissions() != null && !entry.permissions().isEmpty()) {
            Set<LFPermission> permissions = reverseMapPermissions(entry.permissions());
            if (!permissions.isEmpty()) {
                operations.add(new LFPermissionOperation(
                        LFPermissionOperation.OperationType.GRANT,
                        null, // sourcePolicyId is always null for fetched permissions
                        principalArn,
                        resource,
                        permissions,
                        false));
            }
        }

        // Handle permissions with grant option
        if (entry.permissionsWithGrantOption() != null && !entry.permissionsWithGrantOption().isEmpty()) {
            Set<LFPermission> grantablePermissions = reverseMapPermissions(entry.permissionsWithGrantOption());
            if (!grantablePermissions.isEmpty()) {
                operations.add(new LFPermissionOperation(
                        LFPermissionOperation.OperationType.GRANT,
                        null, // sourcePolicyId is always null for fetched permissions
                        principalArn,
                        resource,
                        grantablePermissions,
                        true));
            }
        }

        if (operations.isEmpty()) {
            LOG.warn("Skipping entry where all permissions are unrecognized: principal={}, resource={}",
                    principalArn, sdkResource);
        }

        return operations;
    }

    /**
     * Convert an SDK Resource to an LFResource.
     * This is the reverse of LakeFormationClient.buildResource().
     * Returns null for unsupported resource types (CatalogResource, LFTagPolicy).
     */
    LFResource reverseMapResource(Resource sdkResource) {
        if (sdkResource.database() != null) {
            DatabaseResource db = sdkResource.database();
            return new LFResource(db.catalogId(), db.name(), null, null, null);
        }

        if (sdkResource.table() != null) {
            TableResource table = sdkResource.table();
            // Check for TableWildcard (all tables in database)
            if (table.tableWildcard() != null) {
                return new LFResource(table.catalogId(), table.databaseName(), "*", null, null);
            }
            return new LFResource(table.catalogId(), table.databaseName(), table.name(), null, null);
        }

        if (sdkResource.tableWithColumns() != null) {
            TableWithColumnsResource twc = sdkResource.tableWithColumns();
            // Check for ColumnWildcard (all columns → table-level)
            if (twc.columnWildcard() != null) {
                return new LFResource(twc.catalogId(), twc.databaseName(), twc.name(), null, null);
            }
            Set<String> columnNames = new HashSet<>(twc.columnNames());
            return new LFResource(twc.catalogId(), twc.databaseName(), twc.name(), columnNames, null);
        }

        if (sdkResource.dataLocation() != null) {
            DataLocationResource dl = sdkResource.dataLocation();
            return new LFResource(null, null, null, null, null, dl.resourceArn());
        }

        // CatalogResource and LFTagPolicyResource are not supported
        if (sdkResource.catalog() != null) {
            LOG.warn("Skipping unsupported CatalogResource");
            return null;
        }
        if (sdkResource.lfTagPolicy() != null) {
            LOG.warn("Skipping unsupported LFTagPolicyResource");
            return null;
        }

        LOG.warn("Skipping unknown resource type: {}", sdkResource);
        return null;
    }

    /**
     * Convert SDK Permission enums to LFPermission enums.
     * Skips unrecognized values and logs a warning.
     */
    Set<LFPermission> reverseMapPermissions(List<Permission> sdkPermissions) {
        Set<LFPermission> result = EnumSet.noneOf(LFPermission.class);
        for (Permission sdkPerm : sdkPermissions) {
            try {
                LFPermission mapped = LFPermission.fromValue(sdkPerm.toString());
                result.add(mapped);
            } catch (IllegalArgumentException e) {
                LOG.warn("Skipping unrecognized permission value: {}", sdkPerm);
            }
        }
        return result;
    }


}
