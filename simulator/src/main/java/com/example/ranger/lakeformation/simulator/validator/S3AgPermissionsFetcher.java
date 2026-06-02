package com.example.ranger.lakeformation.simulator.validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.Grantee;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantEntry;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsRequest;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsResponse;
import software.amazon.awssdk.services.s3control.model.S3ControlException;

import java.util.HashSet;
import java.util.Set;

/**
 * Fetches all current S3 Access Grants for a given AWS account.
 * Returns a flat Set<SimulatorPermission> for comparison with expected permissions.
 */
public class S3AgPermissionsFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(S3AgPermissionsFetcher.class);

    private final S3ControlClient s3ControlClient;
    private final String accountId;
    private final String instanceArn;   // S3 Access Grants instance ARN

    public S3AgPermissionsFetcher(S3ControlClient s3ControlClient, String accountId, String instanceArn) {
        this.s3ControlClient = s3ControlClient;
        this.accountId = accountId;
        this.instanceArn = instanceArn;
    }

    /**
     * List all grants from the Access Grants instance and return as SimulatorPermissions.
     * Returns empty set if instanceArn is blank (S3AG not configured), or if no instance exists (404).
     */
    public Set<SimulatorPermission> fetchAll() {
        Set<SimulatorPermission> result = new HashSet<>();
        if (instanceArn == null || instanceArn.isBlank()) {
            LOG.debug("No S3 Access Grants instance ARN configured; skipping S3AG validation");
            return result;
        }

        String continuationToken = null;
        int pageCount = 0;

        do {
            ListAccessGrantsRequest.Builder builder = ListAccessGrantsRequest.builder()
                    .accountId(accountId)
                    .maxResults(100);
            if (continuationToken != null) {
                builder.nextToken(continuationToken);
            }
            ListAccessGrantsResponse response;
            try {
                response = s3ControlClient.listAccessGrants(builder.build());
            } catch (S3ControlException e) {
                if (e.statusCode() == 404) {
                    LOG.debug("No S3 Access Grants instance in account {}; skipping S3AG validation", accountId);
                    return result;
                }
                throw e;
            }

            for (ListAccessGrantEntry entry : response.accessGrantsList()) {
                String granteeArn = extractGranteeArn(entry.grantee());
                if (granteeArn == null) continue;
                String resourceId = entry.accessGrantsLocationConfiguration() != null
                        ? entry.accessGrantsLocationConfiguration().s3SubPrefix()
                        : null;
                if (resourceId == null) resourceId = "unknown";
                String permission = entry.permission() != null ? entry.permission().toString() : "UNKNOWN";
                result.add(new SimulatorPermission(granteeArn, "S3_PREFIX", resourceId, permission, false));
            }

            continuationToken = response.nextToken();
            pageCount++;
        } while (continuationToken != null);

        LOG.info("S3AgPermissionsFetcher: scanned {} pages, {} total grants", pageCount, result.size());
        return result;
    }

    String extractGranteeArn(Grantee grantee) {
        if (grantee == null) return null;
        if (grantee.granteeIdentifier() != null) return grantee.granteeIdentifier();
        return null;
    }
}
