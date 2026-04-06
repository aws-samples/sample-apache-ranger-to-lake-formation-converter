package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a single grant or revoke operation to be applied to Lake Formation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LFPermissionOperation {

    /**
     * The type of Lake Formation permission operation.
     */
    public enum OperationType {
        GRANT("GRANT"),
        REVOKE("REVOKE");

        private final String value;

        OperationType(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static OperationType fromValue(String value) {
            for (OperationType type : values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown OperationType: " + value);
        }
    }

    private final OperationType operationType;
    private final String sourcePolicyId;
    private final String principalArn;
    private final LFResource resource;
    private final Set<LFPermission> permissions;
    private final boolean grantable;

    @JsonCreator
    public LFPermissionOperation(
            @JsonProperty("operationType") OperationType operationType,
            @JsonProperty("sourcePolicyId") String sourcePolicyId,
            @JsonProperty("principalArn") String principalArn,
            @JsonProperty("resource") LFResource resource,
            @JsonProperty("permissions") Set<LFPermission> permissions,
            @JsonProperty("grantable") boolean grantable) {
        this.operationType = operationType;
        this.sourcePolicyId = sourcePolicyId;
        this.principalArn = principalArn;
        this.resource = resource;
        this.permissions = permissions != null
                ? Collections.unmodifiableSet(EnumSet.copyOf(permissions))
                : Collections.<LFPermission>emptySet();
        this.grantable = grantable;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public String getSourcePolicyId() {
        return sourcePolicyId;
    }

    public String getPrincipalArn() {
        return principalArn;
    }

    public LFResource getResource() {
        return resource;
    }

    public Set<LFPermission> getPermissions() {
        return permissions;
    }

    public boolean isGrantable() {
        return grantable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LFPermissionOperation that = (LFPermissionOperation) o;
        return grantable == that.grantable
                && operationType == that.operationType
                && Objects.equals(sourcePolicyId, that.sourcePolicyId)
                && Objects.equals(principalArn, that.principalArn)
                && Objects.equals(resource, that.resource)
                && Objects.equals(permissions, that.permissions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationType, sourcePolicyId, principalArn, resource, permissions, grantable);
    }

    @Override
    public String toString() {
        return "LFPermissionOperation{" +
                "operationType=" + operationType +
                ", sourcePolicyId='" + sourcePolicyId + '\'' +
                ", principalArn='" + principalArn + '\'' +
                ", resource=" + resource +
                ", permissions=" + permissions +
                ", grantable=" + grantable +
                '}';
    }
}
