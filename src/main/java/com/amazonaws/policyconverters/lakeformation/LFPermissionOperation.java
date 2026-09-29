package com.amazonaws.policyconverters.lakeformation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a single grant or revoke operation to be applied to Lake Formation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"operationType", "sourcePolicyId", "originalPrincipal", "principalArn",
        "resource", "permissions", "grantable"})
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
    private final String originalPrincipal;
    private final String principalArn;
    private final LFResource resource;
    private final Set<LFPermission> permissions;
    private final boolean grantable;

    /**
     * Constructs an operation with no original-principal label. Retained so callers that
     * derive operations from other operations (for example the sync diff) need not supply one.
     */
    public LFPermissionOperation(
            OperationType operationType,
            String sourcePolicyId,
            String principalArn,
            LFResource resource,
            Set<LFPermission> permissions,
            boolean grantable) {
        this(operationType, sourcePolicyId, null, principalArn, resource, permissions, grantable);
    }

    @JsonCreator
    public LFPermissionOperation(
            @JsonProperty("operationType") OperationType operationType,
            @JsonProperty("sourcePolicyId") String sourcePolicyId,
            @JsonProperty("originalPrincipal") String originalPrincipal,
            @JsonProperty("principalArn") String principalArn,
            @JsonProperty("resource") LFResource resource,
            @JsonProperty("permissions") Set<LFPermission> permissions,
            @JsonProperty("grantable") boolean grantable) {
        this.operationType = operationType;
        this.sourcePolicyId = sourcePolicyId;
        this.originalPrincipal = originalPrincipal;
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

    /**
     * The Ranger principal name(s) this grant came from, kind-qualified — for example
     * {@code "user:alice_analyst"} or {@code "group:data_analysts"}. Purely descriptive: it
     * exists so a reader can validate output without reverse-resolving an Identity Center
     * UUID. Null when the operation was derived rather than converted (for example a revoke
     * computed by the sync diff), or when read from a checkpoint written before this field
     * existed.
     *
     * <p>Comma-separated when several Ranger principals resolve to the same ARN, which a
     * static mapping can do.
     */
    public String getOriginalPrincipal() {
        return originalPrincipal;
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

    // originalPrincipal is deliberately excluded from equals/hashCode. It is a descriptive
    // label, not part of the grant's identity: what Lake Formation applies is determined by
    // (principalArn, resource, permissions, grantable). Including it would make operations
    // read from an older checkpoint unequal to freshly converted ones carrying the same grant,
    // which the sync diff would resolve as a spurious revoke-and-regrant.
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
                ", originalPrincipal='" + originalPrincipal + '\'' +
                ", principalArn='" + principalArn + '\'' +
                ", resource=" + resource +
                ", permissions=" + permissions +
                ", grantable=" + grantable +
                '}';
    }
}
