package com.example.ranger.lakeformation.simulator.validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lakeformation.LakeFormationClient;
import software.amazon.awssdk.services.lakeformation.model.DataLakePrincipal;
import software.amazon.awssdk.services.lakeformation.model.DataLocationResource;
import software.amazon.awssdk.services.lakeformation.model.DatabaseResource;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.Permission;
import software.amazon.awssdk.services.lakeformation.model.PrincipalResourcePermissions;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.TableResource;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import java.util.HashSet;
import java.util.Set;

/**
 * Fetches all current Lake Formation permissions via a full paginated ListPermissions scan.
 * Returns a flat Set<SimulatorPermission> for comparison with expected permissions.
 */
public class LFPermissionsFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(LFPermissionsFetcher.class);

    private final LakeFormationClient lfClient;
    private final String catalogId;

    public LFPermissionsFetcher(LakeFormationClient lfClient, String catalogId) {
        this.lfClient = lfClient;
        this.catalogId = catalogId;
    }

    /**
     * Perform a full paginated ListPermissions scan and return all effective permissions.
     */
    public Set<SimulatorPermission> fetchAll() {
        Set<SimulatorPermission> result = new HashSet<>();
        String nextToken = null;
        int pageCount = 0;

        do {
            ListPermissionsRequest.Builder builder = ListPermissionsRequest.builder()
                    .catalogId(catalogId)
                    .maxResults(100);
            if (nextToken != null) {
                builder.nextToken(nextToken);
            }
            ListPermissionsResponse response = lfClient.listPermissions(builder.build());

            for (PrincipalResourcePermissions entry : response.principalResourcePermissions()) {
                String principalArn = extractPrincipalArn(entry.principal());
                if (principalArn == null) continue;

                String resourceType = resolveResourceType(entry.resource());
                String resourceId = resolveResourceId(entry.resource());
                if (resourceType == null || resourceId == null) continue;

                for (Permission perm : entry.permissions()) {
                    boolean grantable = entry.permissionsWithGrantOption() != null
                            && entry.permissionsWithGrantOption().contains(perm);
                    result.add(new SimulatorPermission(principalArn, resourceType, resourceId, perm.toString(), grantable));
                }
            }

            nextToken = response.nextToken();
            pageCount++;
        } while (nextToken != null);

        LOG.info("LFPermissionsFetcher: scanned {} pages, {} total permissions", pageCount, result.size());
        return result;
    }

    private String extractPrincipalArn(DataLakePrincipal principal) {
        if (principal == null) return null;
        return principal.dataLakePrincipalIdentifier();
    }

    String resolveResourceType(Resource resource) {
        if (resource == null) return null;
        if (resource.table() != null) return "TABLE";
        if (resource.database() != null) return "DATABASE";
        if (resource.dataLocation() != null) return "DATA_LOCATION";
        if (resource.tableWithColumns() != null) return "TABLE_WITH_COLUMNS";
        return "OTHER";
    }

    String resolveResourceId(Resource resource) {
        if (resource == null) return null;
        if (resource.table() != null) {
            TableResource t = resource.table();
            return t.databaseName() + "." + (t.name() != null ? t.name() : "*");
        }
        if (resource.database() != null) {
            return resource.database().name();
        }
        if (resource.dataLocation() != null) {
            // LF stores the ARN (arn:aws:s3:::bucket/path); normalize back to s3:// URL
            // with trailing slash to match Ranger policy datalocation resource values.
            String arn = resource.dataLocation().resourceArn();
            if (arn != null && arn.startsWith("arn:aws:s3:::")) {
                String s3Path = "s3://" + arn.substring("arn:aws:s3:::".length());
                if (!s3Path.endsWith("/")) s3Path += "/";
                return s3Path;
            }
            return arn;
        }
        if (resource.tableWithColumns() != null) {
            TableWithColumnsResource twc = resource.tableWithColumns();
            return twc.databaseName() + "." + twc.name();
        }
        return "unknown";
    }
}
