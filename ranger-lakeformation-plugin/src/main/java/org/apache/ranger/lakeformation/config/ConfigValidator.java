package org.apache.ranger.lakeformation.config;

import org.apache.ranger.lakeformation.model.AwsConfig;
import org.apache.ranger.lakeformation.model.RangerConnectionConfig;
import org.apache.ranger.lakeformation.model.SyncConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link SyncConfig} at startup, collecting all validation errors
 * before any operations begin. Returns all errors at once so the operator
 * can fix everything in a single pass.
 */
public class ConfigValidator {

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

        return errors;
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

        boolean hasStaticCreds = !isBlank(awsConfig.getAccessKey()) && !isBlank(awsConfig.getSecretKey());
        boolean hasRoleArn = !isBlank(awsConfig.getRoleArn());

        if (!hasStaticCreds && !hasRoleArn) {
            errors.add("Missing required parameter: awsConfig must have either "
                    + "accessKey+secretKey or roleArn");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
