package com.amazonaws.policyconverters.lakeformation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Configurable filter for scoped permission retrieval and drift exclusion.
 * Used by the reverse-sync pipeline to narrow which LakeFormation permissions
 * are fetched and which are excluded from drift computation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionFilter {

    private final String principalArn;
    private final String resourceType;
    private final Set<String> excludedPrincipals;
    private final Set<String> excludedResourcePatterns;

    @JsonCreator
    public PermissionFilter(
            @JsonProperty("principalArn") String principalArn,
            @JsonProperty("resourceType") String resourceType,
            @JsonProperty("excludedPrincipals") Set<String> excludedPrincipals,
            @JsonProperty("excludedResourcePatterns") Set<String> excludedResourcePatterns) {
        this.principalArn = principalArn;
        this.resourceType = resourceType;
        this.excludedPrincipals = excludedPrincipals != null
                ? Collections.unmodifiableSet(new HashSet<>(excludedPrincipals))
                : Collections.emptySet();
        this.excludedResourcePatterns = excludedResourcePatterns != null
                ? Collections.unmodifiableSet(new HashSet<>(excludedResourcePatterns))
                : Collections.emptySet();
    }

    public String getPrincipalArn() {
        return principalArn;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Set<String> getExcludedPrincipals() {
        return excludedPrincipals;
    }

    public Set<String> getExcludedResourcePatterns() {
        return excludedResourcePatterns;
    }

    /**
     * Returns true if the given operation should be excluded from drift computation.
     * An operation is excluded if:
     * <ul>
     *   <li>Its principal ARN is in {@code excludedPrincipals}, OR</li>
     *   <li>Its resource matches any pattern in {@code excludedResourcePatterns}</li>
     * </ul>
     *
     * Resource patterns use simple glob matching where {@code *} matches any
     * sequence of characters. The resource is represented as a path string
     * built from its database, table, and column components.
     */
    public boolean shouldExclude(LFPermissionOperation op) {
        if (op == null) {
            return false;
        }

        // Check principal exclusion
        if (op.getPrincipalArn() != null && excludedPrincipals.contains(op.getPrincipalArn())) {
            return true;
        }

        // Check resource pattern exclusion
        if (!excludedResourcePatterns.isEmpty() && op.getResource() != null) {
            String resourcePath = buildResourcePath(op.getResource());
            for (String pattern : excludedResourcePatterns) {
                if (matchesGlob(resourcePath, pattern)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Builds a path string from an LFResource for pattern matching.
     * Format: "databaseName/tableName/col1,col2" or "s3://path" for data locations.
     */
    static String buildResourcePath(LFResource resource) {
        if (resource.getDataLocationPath() != null) {
            return resource.getDataLocationPath();
        }

        StringBuilder sb = new StringBuilder();
        if (resource.getDatabaseName() != null) {
            sb.append(resource.getDatabaseName());
        }
        if (resource.getTableName() != null) {
            sb.append('/').append(resource.getTableName());
        }
        if (resource.getColumnNames() != null && !resource.getColumnNames().isEmpty()) {
            sb.append('/').append(String.join(",", resource.getColumnNames()));
        }
        return sb.toString();
    }

    /**
     * Simple glob matching where {@code *} matches any sequence of characters.
     */
    static boolean matchesGlob(String text, String pattern) {
        // Convert glob pattern to regex: escape regex chars, replace * with .*
        String regex = pattern
                .replace(".", "\\.")
                .replace("?", "\\?")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("+", "\\+")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("|", "\\|")
                .replace("*", ".*");
        return text.matches(regex);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PermissionFilter that = (PermissionFilter) o;
        return Objects.equals(principalArn, that.principalArn)
                && Objects.equals(resourceType, that.resourceType)
                && Objects.equals(excludedPrincipals, that.excludedPrincipals)
                && Objects.equals(excludedResourcePatterns, that.excludedResourcePatterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principalArn, resourceType, excludedPrincipals, excludedResourcePatterns);
    }

    @Override
    public String toString() {
        return "PermissionFilter{" +
                "principalArn='" + principalArn + '\'' +
                ", resourceType='" + resourceType + '\'' +
                ", excludedPrincipals=" + excludedPrincipals +
                ", excludedResourcePatterns=" + excludedResourcePatterns +
                '}';
    }
}
