package org.apache.ranger.lakeformation.config;

import org.apache.ranger.lakeformation.model.AwsConfig;
import org.apache.ranger.lakeformation.model.RangerConnectionConfig;
import org.apache.ranger.lakeformation.model.SyncConfig;
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
    void missingBothAwsCredentialMethods_reportsError() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "pass", null, null,
                "us-east-1", "123", null, null, null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("accessKey+secretKey or roleArn")));
    }

    @Test
    void partialStaticCreds_accessKeyOnly_reportsError() {
        SyncConfig config = buildConfig(
                "https://ranger:6080", "admin", "pass", null, null,
                "us-east-1", "123", "AK", null, null);

        List<String> errors = validator.validate(config);

        assertTrue(errors.stream().anyMatch(e -> e.contains("accessKey+secretKey or roleArn")));
    }

    @Test
    void allFieldsMissing_reportsAllErrors() {
        SyncConfig config = new SyncConfig(
                new RangerConnectionConfig(null, null, null, null, null, null, null),
                new AwsConfig(null, null, null, null, null),
                null, null, null, null, null);

        List<String> errors = validator.validate(config);

        // Should report: rangerAdminUrl missing, no auth method, region missing, catalogId missing, no aws creds
        assertEquals(5, errors.size());
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
}
