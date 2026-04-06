package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Configuration for connecting to Apache Ranger Admin.
 * Supports both username/password and Kerberos authentication.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RangerConnectionConfig {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 1000L;

    private final String rangerAdminUrl;
    private final String username;
    private final String password;
    private final String kerberosKeytab;
    private final String kerberosPrincipal;
    private final int maxRetries;
    private final long retryBackoffMs;

    @JsonCreator
    public RangerConnectionConfig(
            @JsonProperty("rangerAdminUrl") String rangerAdminUrl,
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("kerberosKeytab") String kerberosKeytab,
            @JsonProperty("kerberosPrincipal") String kerberosPrincipal,
            @JsonProperty("maxRetries") Integer maxRetries,
            @JsonProperty("retryBackoffMs") Long retryBackoffMs) {
        this.rangerAdminUrl = rangerAdminUrl;
        this.username = username;
        this.password = password;
        this.kerberosKeytab = kerberosKeytab;
        this.kerberosPrincipal = kerberosPrincipal;
        this.maxRetries = maxRetries != null ? maxRetries : DEFAULT_MAX_RETRIES;
        this.retryBackoffMs = retryBackoffMs != null ? retryBackoffMs : DEFAULT_RETRY_BACKOFF_MS;
    }

    public String getRangerAdminUrl() {
        return rangerAdminUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getKerberosKeytab() {
        return kerberosKeytab;
    }

    public String getKerberosPrincipal() {
        return kerberosPrincipal;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RangerConnectionConfig that = (RangerConnectionConfig) o;
        return maxRetries == that.maxRetries
                && retryBackoffMs == that.retryBackoffMs
                && Objects.equals(rangerAdminUrl, that.rangerAdminUrl)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(kerberosKeytab, that.kerberosKeytab)
                && Objects.equals(kerberosPrincipal, that.kerberosPrincipal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rangerAdminUrl, username, password,
                kerberosKeytab, kerberosPrincipal, maxRetries, retryBackoffMs);
    }

    @Override
    public String toString() {
        return "RangerConnectionConfig{" +
                "rangerAdminUrl='" + rangerAdminUrl + '\'' +
                ", username='" + username + '\'' +
                ", password='****'" +
                ", kerberosKeytab='" + kerberosKeytab + '\'' +
                ", kerberosPrincipal='" + kerberosPrincipal + '\'' +
                ", maxRetries=" + maxRetries +
                ", retryBackoffMs=" + retryBackoffMs +
                '}';
    }
}
