package com.amazonaws.policyconverters.config;

import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.config.SyncConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a {@link SyncConfig} at startup, collecting all validation errors
 * before any operations begin. Returns all errors at once so the operator
 * can fix everything in a single pass.
 */
public class ConfigValidator {

    private static final Logger logger = LoggerFactory.getLogger(ConfigValidator.class);

    private static final Set<String> ALLOWED_SERVICE_TYPES = Set.of(
            "lakeformation", "hive", "presto", "trino");

    private static final Set<String> CATALOG_REQUIRED_SERVICE_TYPES = Set.of(
            "presto", "trino");
    /**
     * Validate the given configuration and return a list of descriptive error messages.
     * An empty list means the configuration is valid.
     *
     * @param config the configuration to validate
     * @return list of validation error messages (empty if valid)
     */
    public List<String> validate(SyncConfig config) {
        List<String> errors = new ArrayList<>();

        if (config == null) {
            errors.add("Missing required parameter: config (SyncConfig is null)");
            return errors;
        }

        validateRangerConfig(config.getRangerConfig(), errors);
        validateAwsConfig(config.getAwsConfig(), errors);

        if (config.getWildcardRefreshIntervalSeconds() < 0) {
            errors.add("Invalid parameter: wildcardRefreshIntervalSeconds must be >= 0");
        }

        if (config.getRangerServices() != null && !config.getRangerServices().isEmpty()) {
            validateRangerServices(config.getRangerServices(), errors);
        }

        return errors;
    }

    /**
     * Validate the {@code rangerServices} list for duplicates, unknown service types,
     * missing instance names, and missing gdcCatalogName for presto/trino entries.
     *
     * @param rangerServices the list of service configurations to validate
     * @param errors         the error list to append to
     */
    void validateRangerServices(List<RangerServiceConfig> rangerServices, List<String> errors) {
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < rangerServices.size(); i++) {
            RangerServiceConfig entry = rangerServices.get(i);
            String prefix = "rangerServices[" + i + "]: ";

            if (isBlank(entry.getServiceInstanceName())) {
                errors.add(prefix + "missing required serviceInstanceName");
            }

            String serviceType = entry.getServiceType();
            if (isBlank(serviceType)) {
                errors.add(prefix + "missing required serviceType");
                continue;
            }

            if (!ALLOWED_SERVICE_TYPES.contains(serviceType)) {
                errors.add(prefix + "unknown serviceType '" + serviceType
                        + "'; allowed values: " + ALLOWED_SERVICE_TYPES);
            }

            if (!isBlank(entry.getServiceInstanceName())) {
                String key = serviceType + "+" + entry.getServiceInstanceName();
                if (!seen.add(key)) {
                    errors.add(prefix + "duplicate serviceType+serviceInstanceName pair '"
                            + serviceType + "+" + entry.getServiceInstanceName() + "'");
                }
            }

            if (CATALOG_REQUIRED_SERVICE_TYPES.contains(serviceType)
                    && isBlank(entry.getGdcCatalogName())) {
                errors.add(prefix + "serviceType '" + serviceType
                        + "' requires gdcCatalogName");
            }
        }
    }

    private void validateRangerConfig(RangerConnectionConfig rangerConfig, List<String> errors) {
        if (rangerConfig == null) {
            errors.add("Missing required parameter: rangerConfig");
            return;
        }

        if (isBlank(rangerConfig.getRangerAdminUrl())) {
            errors.add("Missing required parameter: rangerConfig.rangerAdminUrl");
        } else if (!rangerConfig.getRangerAdminUrl().startsWith("http://")
                && !rangerConfig.getRangerAdminUrl().startsWith("https://")) {
            errors.add("Invalid parameter: rangerConfig.rangerAdminUrl must start with http:// or https://");
        }

        boolean hasBasicAuth = !isBlank(rangerConfig.getUsername()) && !isBlank(rangerConfig.getPassword());
        boolean hasKerberosAuth = !isBlank(rangerConfig.getKerberosKeytab())
                && !isBlank(rangerConfig.getKerberosPrincipal());

        if (!hasBasicAuth && !hasKerberosAuth) {
            errors.add("Missing required parameter: rangerConfig must have either "
                    + "username+password or kerberosKeytab+kerberosPrincipal");
        }
    }

    private void validateAwsConfig(AwsConfig awsConfig, List<String> errors) {
        if (awsConfig == null) {
            errors.add("Missing required parameter: awsConfig");
            return;
        }

        if (isBlank(awsConfig.getRegion())) {
            errors.add("Missing required parameter: awsConfig.region");
        }

        if (isBlank(awsConfig.getCatalogId())) {
            errors.add("Missing required parameter: awsConfig.catalogId");
        }

        if (!isBlank(awsConfig.getAccessKey()) && !isBlank(awsConfig.getSecretKey())){
            logger.info("Using Static AWS credentials for AWS access");
        } else if (!isBlank(awsConfig.getRoleArn())) {
            logger.info("Using IAM role : " + awsConfig.getRoleArn() + " for AWS access");
        } else {
            logger.info("Using default credentials provider for AWS access. ");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
