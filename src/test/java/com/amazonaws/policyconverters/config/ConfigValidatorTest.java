package com.amazonaws.policyconverters.config;

import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    private ConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConfigValidator();
    }

    @Test
    void validConfig_withBasicAuthAndStaticCreds_returnsNoErrors() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "secret", null, null,
                "us-east-1", "123456789012", "AKIA123", "secretKey", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void validConfig_withKerberosAndRoleArn_returnsNoErrors() {
        SyncConfig config = buildConfig(
                "http://ranger:6080", null, null, "/etc/keytab", "user@REALM",
                "eu-west-1", "987654321098", null, null, "arn:aws:iam::123:role/R");

        List<String> errors = validator.validate(config);

        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void nullConfig_returnsSingleError() {
        List<String> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("SyncConfig is null"));
    }

    @Test
    void nullRangerConfig_reportsError() {
        SyncConfig config = new SyncConfig(
                null,
                new AwsConfig("us-east-1", "123", "AK", "SK", null),
                null, null, null, null, null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("rangerConfig")));
    }

    @Test
    void nullAwsConfig_reportsError() {
        SyncConfig config = new SyncConfig(
                new RangerConnectionConfig("https://ranger:6080", "admin", "pass", null, null, null, null),
                null,
                null, null, null, null, null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("awsConfig")));
    }

    @Test
    void missingRangerAdminUrl_reportsError() {
        SyncConfig config = buildConfig(
                null, "admin", "secret", null, null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("rangerConfig.rangerAdminUrl")));
    }

    @Test
    void blankRangerAdminUrl_reportsError() {
        SyncConfig config = buildConfig(
                "  ", "admin", "secret", null, null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("rangerConfig.rangerAdminUrl")));
    }

    @Test
    void invalidRangerAdminUrl_notHttp_reportsError() {
        SyncConfig config = buildConfig(
                "ftp://ranger:6080", "admin", "secret", null, null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("must start with http:// or https://")));
    }

    @Test
    void missingBothAuthMethods_reportsError() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", null, null, null, null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("username+password or kerberosKeytab+kerberosPrincipal")));
    }

    @Test
    void partialBasicAuth_usernameOnly_reportsError() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", null, null, null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("username+password or kerberosKeytab+kerberosPrincipal")));
    }

    @Test
    void partialKerberos_keytabOnly_reportsError() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", null, null, "/etc/keytab", null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("username+password or kerberosKeytab+kerberosPrincipal")));
    }

    @Test
    void missingAwsRegion_reportsError() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "pass", null, null,
                null, "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("awsConfig.region")));
    }

    @Test
    void missingAwsCatalogId_reportsError() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "pass", null, null,
                "us-east-1", null, "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("awsConfig.catalogId")));
    }

    @Test
    void missingBothAwsCredentialMethods_usesDefaultProvider() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "pass", null, null,
                "us-east-1", "123", null, null, null);

        List<String> errors = validator.validate(config);

        // No error — falls through to default credential provider
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void partialStaticCreds_accessKeyOnly_usesDefaultProvider() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "pass", null, null,
                "us-east-1", "123", "AK", null, null);

        List<String> errors = validator.validate(config);

        // No error — partial static creds are ignored, falls through to default provider
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void allFieldsMissing_reportsAllErrors() {
        SyncConfig config = new SyncConfig(
                new RangerConnectionConfig(null, null, null, null, null, null, null),
                new AwsConfig(null, null, null, null, null),
                null, null, null, null, null);

        List<String> errors = validator.validate(config);

        // Should report: rangerAdminUrl missing, no auth method, region missing, catalogId missing
        assertEquals(4, errors.size());
    }

    @Test
    void bothNullSubConfigs_reportsMultipleErrors() {
        SyncConfig config = new SyncConfig(null, null, null, null, null, null, null);

        List<String> errors = validator.validate(config);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("rangerConfig")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("awsConfig")));
    }

    @Test
    void httpUrl_isValid() {
        SyncConfig config = buildConfig(
                "http://ranger:6080", "admin", "pass", null, null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.isEmpty());
    }

    @Test
    void httpsUrl_isValid() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "pass", null, null,
                "us-east-1", "123", "AK", "SK", null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.isEmpty());
    }

    // --- wildcardRefreshIntervalSeconds validation tests (Task 2.4) ---

    @Test
    void negativeWildcardRefreshInterval_reportsError() {
        SyncConfig config = buildConfigWithWildcardInterval(-1);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("wildcardRefreshIntervalSeconds must be >= 0")),
                "Expected validation error for negative interval but got: " + errors);
    }

    @Test
    void zeroWildcardRefreshInterval_returnsNoErrors() {
        SyncConfig config = buildConfigWithWildcardInterval(0);

        List<String> errors = validator.validate(config);

        assertTrue(errors.isEmpty(), "Expected no errors for zero interval but got: " + errors);
    }

    @Test
    void positiveWildcardRefreshInterval_returnsNoErrors() {
        SyncConfig config = buildConfigWithWildcardInterval(300);

        List<String> errors = validator.validate(config);

        assertTrue(errors.isEmpty(), "Expected no errors for positive interval but got: " + errors);
    }

    @Test
    void largeNegativeWildcardRefreshInterval_reportsError() {
        SyncConfig config = buildConfigWithWildcardInterval(Integer.MIN_VALUE);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("wildcardRefreshIntervalSeconds must be >= 0")));
    }

    private SyncConfig buildConfig(
            String rangerUrl, String username, String password,
            String keytab, String principal,
            String region, String catalogId,
            String accessKey, String secretKey, String roleArn) {
        RangerConnectionConfig rangerConfig = new RangerConnectionConfig(
                rangerUrl, username, password, keytab, principal, null, null);
        AwsConfig awsConfig = new AwsConfig(region, catalogId, accessKey, secretKey, roleArn);
        return new SyncConfig(rangerConfig, awsConfig, null, null, null, null, null);
    }

    private SyncConfig buildConfigWithWildcardInterval(int intervalSeconds) {
        RangerConnectionConfig rangerConfig = new RangerConnectionConfig(
                "https://ranger:6080", "admin", "secret", null, null, null, null);
        AwsConfig awsConfig = new AwsConfig("us-east-1", "123456789012", "AKIA123", "secretKey", null);
        return new SyncConfig(rangerConfig, awsConfig, null, null, null, null, null, null,
                intervalSeconds);
    }
}
