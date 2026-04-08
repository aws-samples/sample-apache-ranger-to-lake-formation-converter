package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Structured summary of detected differences between Cedar-derived desired
 * permissions and actual LakeFormation permissions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DriftReport {

    private final int missingGrants;
    private final int extraPermissions;
    private final int inSyncCount;
    private final List<LFPermissionOperation> skippedPermissions;
    private final List<FailedOperation> failedOperations;

    @JsonCreator
    public DriftReport(
            @JsonProperty("missingGrants") int missingGrants,
            @JsonProperty("extraPermissions") int extraPermissions,
            @JsonProperty("inSyncCount") int inSyncCount,
            @JsonProperty("skippedPermissions") List<LFPermissionOperation> skippedPermissions,
            @JsonProperty("failedOperations") List<FailedOperation> failedOperations) {
        this.missingGrants = missingGrants;
        this.extraPermissions = extraPermissions;
        this.inSyncCount = inSyncCount;
        this.skippedPermissions = skippedPermissions != null
                ? Collections.unmodifiableList(new ArrayList<>(skippedPermissions))
                : Collections.emptyList();
        this.failedOperations = failedOperations != null
                ? Collections.unmodifiableList(new ArrayList<>(failedOperations))
                : Collections.emptyList();
    }

    public int getMissingGrants() {
        return missingGrants;
    }

    public int getExtraPermissions() {
        return extraPermissions;
    }

    public int getInSyncCount() {
        return inSyncCount;
    }

    public List<LFPermissionOperation> getSkippedPermissions() {
        return skippedPermissions;
    }

    public List<FailedOperation> getFailedOperations() {
        return failedOperations;
    }

    /**
     * Returns the total number of drifted permissions (missing grants + extra permissions).
     */
    public int getTotalDrift() {
        return missingGrants + extraPermissions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DriftReport that = (DriftReport) o;
        return missingGrants == that.missingGrants
                && extraPermissions == that.extraPermissions
                && inSyncCount == that.inSyncCount
                && Objects.equals(skippedPermissions, that.skippedPermissions)
                && Objects.equals(failedOperations, that.failedOperations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(missingGrants, extraPermissions, inSyncCount, skippedPermissions, failedOperations);
    }

    @Override
    public String toString() {
        return "DriftReport{" +
                "missingGrants=" + missingGrants +
                ", extraPermissions=" + extraPermissions +
                ", inSyncCount=" + inSyncCount +
                ", skippedPermissions=" + skippedPermissions +
                ", failedOperations=" + failedOperations +
                '}';
    }

    /**
     * Represents a corrective operation that failed during application.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FailedOperation {

        private final LFPermissionOperation operation;
        private final String error;

        @JsonCreator
        public FailedOperation(
                @JsonProperty("operation") LFPermissionOperation operation,
                @JsonProperty("error") String error) {
            this.operation = operation;
            this.error = error;
        }

        public LFPermissionOperation getOperation() {
            return operation;
        }

        public String getError() {
            return error;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FailedOperation that = (FailedOperation) o;
            return Objects.equals(operation, that.operation)
                    && Objects.equals(error, that.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operation, error);
        }

        @Override
        public String toString() {
            return "FailedOperation{" +
                    "operation=" + operation +
                    ", error='" + error + '\'' +
                    '}';
        }
    }
}
