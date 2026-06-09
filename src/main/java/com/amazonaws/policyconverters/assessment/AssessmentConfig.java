package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.S3AccessGrantsConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Configuration for a one-time Ranger policy gap assessment run.
 */
public class AssessmentConfig {

    private final String rangerAdminUrl;
    private final String rangerUsername;
    private final String rangerPassword;
    private final List<RangerServiceConfig> services;
    private final Optional<AwsConfig> awsConfig;
    private final PrincipalMappingConfig principalMapping;
    private final Path outputDir;
    private final boolean consoleOnly;
    private final S3AccessGrantsConfig s3AccessGrants;

    private AssessmentConfig(Builder builder) {
        this.rangerAdminUrl = builder.rangerAdminUrl;
        this.rangerUsername = builder.rangerUsername;
        this.rangerPassword = builder.rangerPassword;
        this.services = Collections.unmodifiableList(builder.services);
        this.awsConfig = builder.awsConfig;
        this.principalMapping = builder.principalMapping;
        this.outputDir = builder.outputDir;
        this.consoleOnly = builder.consoleOnly;
        this.s3AccessGrants = builder.s3AccessGrants;
    }

    public String getRangerAdminUrl() {
        return rangerAdminUrl;
    }

    public String getRangerUsername() {
        return rangerUsername;
    }

    public String getRangerPassword() {
        return rangerPassword;
    }

    public List<RangerServiceConfig> getServices() {
        return services;
    }

    public Optional<AwsConfig> getAwsConfig() {
        return awsConfig;
    }

    public PrincipalMappingConfig getPrincipalMapping() {
        return principalMapping;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public boolean isConsoleOnly() {
        return consoleOnly;
    }

    public S3AccessGrantsConfig getS3AccessGrants() {
        return s3AccessGrants;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String rangerAdminUrl;
        private String rangerUsername;
        private String rangerPassword;
        private List<RangerServiceConfig> services = Collections.emptyList();
        private Optional<AwsConfig> awsConfig = Optional.empty();
        private PrincipalMappingConfig principalMapping = new PrincipalMappingConfig(null, null, null);
        private Path outputDir = Paths.get(".");
        private boolean consoleOnly = false;
        private S3AccessGrantsConfig s3AccessGrants = null;

        public Builder rangerAdminUrl(String rangerAdminUrl) {
            this.rangerAdminUrl = rangerAdminUrl;
            return this;
        }

        public Builder rangerUsername(String rangerUsername) {
            this.rangerUsername = rangerUsername;
            return this;
        }

        public Builder rangerPassword(String rangerPassword) {
            this.rangerPassword = rangerPassword;
            return this;
        }

        public Builder services(List<RangerServiceConfig> services) {
            this.services = services != null ? services : Collections.emptyList();
            return this;
        }

        public Builder awsConfig(AwsConfig awsConfig) {
            this.awsConfig = Optional.ofNullable(awsConfig);
            return this;
        }

        public Builder principalMapping(PrincipalMappingConfig principalMapping) {
            this.principalMapping = principalMapping != null
                    ? principalMapping
                    : new PrincipalMappingConfig(null, null, null);
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir != null ? outputDir : Paths.get(".");
            return this;
        }

        public Builder consoleOnly(boolean consoleOnly) {
            this.consoleOnly = consoleOnly;
            return this;
        }

        public Builder s3AccessGrants(S3AccessGrantsConfig s3AccessGrants) {
            this.s3AccessGrants = s3AccessGrants;
            return this;
        }

        public AssessmentConfig build() {
            return new AssessmentConfig(this);
        }
    }
}
