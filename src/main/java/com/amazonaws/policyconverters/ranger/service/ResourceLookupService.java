package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.ranger.CatalogResolver;
import org.apache.ranger.plugin.service.RangerBaseService;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.GlueClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extends RangerBaseService to provide resource lookup for the Lake Formation
 * service type in Ranger Admin UI. Queries the AWS Glue Data Catalog to list
 * databases, tables, and columns for resource browsing.
 * <p>
 * AWS credentials are extracted from the service definition configuration
 * properties (aws.region, aws.catalog.id, aws.access.key, aws.secret.key,
 * aws.role.arn).
 */
public class ResourceLookupService extends RangerBaseService {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceLookupService.class);

    static final String CONFIG_AWS_REGION = "aws.region";
    static final String CONFIG_CATALOG_ID = "aws.catalog.id";
    static final String CONFIG_ACCESS_KEY = "aws.access.key";
    static final String CONFIG_SECRET_KEY = "aws.secret.key";
    static final String CONFIG_ROLE_ARN = "aws.role.arn";

    static final String RESOURCE_DATABASE = "database";
    static final String RESOURCE_TABLE = "table";
    static final String RESOURCE_COLUMN = "column";

    private final GlueClientFactory glueClientFactory;

    /**
     * Factory interface for creating GlueClient instances, enabling testability.
     */
    @FunctionalInterface
    public interface GlueClientFactory {
        GlueClient create(String region, AwsCredentialsProvider credentialsProvider);
    }

    public ResourceLookupService() {
        this((region, credentialsProvider) -> {
            GlueClientBuilder builder = GlueClient.builder()
                    .region(Region.of(region));
            if (credentialsProvider != null) {
                builder.credentialsProvider(credentialsProvider);
            }
            return builder.build();
        });
    }

    ResourceLookupService(GlueClientFactory glueClientFactory) {
        this.glueClientFactory = glueClientFactory;
    }

    /**
     * Validates the AWS configuration from the service definition properties.
     * Verifies that at minimum the AWS region is configured and attempts to
     * create a Glue client to confirm connectivity.
     *
     * @return a map with a "connectivityStatus" key indicating success or failure
     * @throws Exception if validation fails
     */
    @Override
    public Map<String, Object> validateConfig() throws Exception {
        Map<String, Object> result = new HashMap<>();

        Map<String, String> configs = getConfigs();
        if (configs == null) {
            configs = Collections.emptyMap();
        }

        String region = configs.get(CONFIG_AWS_REGION);
        if (region == null || region.trim().isEmpty()) {
            String msg = "Required configuration property '" + CONFIG_AWS_REGION + "' is missing or empty";
            LOG.error(msg);
            result.put("connectivityStatus", false);
            result.put("message", msg);
            return result;
        }

        try {
            AwsCredentialsProvider credentialsProvider = buildCredentialsProvider(configs);
            GlueClient glueClient = glueClientFactory.create(region.trim(), credentialsProvider);
            try {
                // Attempt a lightweight call to verify connectivity
                glueClient.getDatabases(
                        software.amazon.awssdk.services.glue.model.GetDatabasesRequest.builder()
                                .maxResults(1)
                                .build()
                );
                LOG.info("Successfully validated AWS configuration for region '{}'", region);
                result.put("connectivityStatus", true);
                result.put("message", "Connection successful");
            } finally {
                glueClient.close();
            }
        } catch (Exception e) {
            String msg = "Failed to validate AWS configuration: " + e.getMessage();
            LOG.error(msg, e);
            result.put("connectivityStatus", false);
            result.put("message", msg);
        }

        return result;
    }

    /**
     * Looks up resources in the Glue Data Catalog based on the resource type
     * specified in the ResourceLookupContext. Supports database, table, and
     * column resource types.
     *
     * @param context the lookup context containing resource type and user input
     * @return list of matching resource names
     * @throws Exception if lookup fails
     */
    @Override
    public List<String> lookupResource(ResourceLookupContext context) throws Exception {
        if (context == null) {
            return Collections.emptyList();
        }

        String resourceName = context.getResourceName();
        if (resourceName == null || resourceName.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, String> configs = getConfigs();
        if (configs == null) {
            configs = Collections.emptyMap();
        }

        String region = configs.get(CONFIG_AWS_REGION);
        if (region == null || region.trim().isEmpty()) {
            LOG.error("Cannot perform resource lookup: '{}' is not configured", CONFIG_AWS_REGION);
            return Collections.emptyList();
        }

        String userInput = context.getUserInput();
        String lookupPattern = (userInput != null && !userInput.isEmpty()) ? userInput + "*" : "*";

        GlueClient glueClient = null;
        try {
            AwsCredentialsProvider credentialsProvider = buildCredentialsProvider(configs);
            glueClient = glueClientFactory.create(region.trim(), credentialsProvider);
            CatalogResolver catalogResolver = new CatalogResolver(glueClient);

            Map<String, List<String>> resources = context.getResources();

            switch (resourceName) {
                case RESOURCE_DATABASE:
                    return catalogResolver.expandDatabases(lookupPattern);

                case RESOURCE_TABLE: {
                    List<String> databases = getSelectedResources(resources, RESOURCE_DATABASE);
                    if (databases.isEmpty()) {
                        return Collections.emptyList();
                    }
                    List<String> results = new ArrayList<>();
                    for (String db : databases) {
                        results.addAll(catalogResolver.expandTables(db, lookupPattern));
                    }
                    return results;
                }

                case RESOURCE_COLUMN: {
                    List<String> databases = getSelectedResources(resources, RESOURCE_DATABASE);
                    List<String> tables = getSelectedResources(resources, RESOURCE_TABLE);
                    if (databases.isEmpty() || tables.isEmpty()) {
                        return Collections.emptyList();
                    }
                    List<String> results = new ArrayList<>();
                    for (String db : databases) {
                        for (String table : tables) {
                            results.addAll(catalogResolver.expandColumns(db, table, lookupPattern));
                        }
                    }
                    return results;
                }

                default:
                    LOG.warn("Unknown resource type for lookup: '{}'", resourceName);
                    return Collections.emptyList();
            }
        } catch (Exception e) {
            LOG.error("Resource lookup failed for type '{}': {}", resourceName, e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            if (glueClient != null) {
                try {
                    glueClient.close();
                } catch (Exception e) {
                    LOG.debug("Error closing GlueClient", e);
                }
            }
        }
    }

    /**
     * Build an AWS credentials provider from the service definition config properties.
     * Priority:
     * 1. If roleArn is set, use STS AssumeRole (with static creds as base if provided)
     * 2. If accessKey and secretKey are set, use static credentials
     * 3. Otherwise, use the default credentials provider chain
     */
    AwsCredentialsProvider buildCredentialsProvider(Map<String, String> configs) {
        String accessKey = configs.get(CONFIG_ACCESS_KEY);
        String secretKey = configs.get(CONFIG_SECRET_KEY);
        String roleArn = configs.get(CONFIG_ROLE_ARN);
        String region = configs.get(CONFIG_AWS_REGION);

        boolean hasStaticCreds = accessKey != null && !accessKey.trim().isEmpty()
                && secretKey != null && !secretKey.trim().isEmpty();

        if (roleArn != null && !roleArn.trim().isEmpty()) {
            LOG.info("Using STS AssumeRole credentials with role ARN: {}", roleArn);
            StsClient stsClient = buildStsClient(region, hasStaticCreds ? accessKey : null,
                    hasStaticCreds ? secretKey : null);

            return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(AssumeRoleRequest.builder()
                            .roleArn(roleArn.trim())
                            .roleSessionName("ranger-lakeformation-lookup")
                            .build())
                    .build();
        }

        if (hasStaticCreds) {
            LOG.info("Using static AWS credentials");
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey.trim(), secretKey.trim()));
        }

        LOG.info("Using default AWS credentials provider chain");
        return DefaultCredentialsProvider.create();
    }

    private List<String> getSelectedResources(Map<String, List<String>> resources, String resourceType) {
        if (resources == null) {
            return Collections.emptyList();
        }
        List<String> values = resources.get(resourceType);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values;
    }

    private StsClient buildStsClient(String region, String accessKey, String secretKey) {
        if (accessKey != null && secretKey != null) {
            if (region != null && !region.trim().isEmpty()) {
                return StsClient.builder()
                        .region(Region.of(region.trim()))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())))
                        .build();
            }
            return StsClient.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())))
                    .build();
        }
        if (region != null && !region.trim().isEmpty()) {
            return StsClient.builder()
                    .region(Region.of(region.trim()))
                    .build();
        }
        return StsClient.builder().build();
    }
}
