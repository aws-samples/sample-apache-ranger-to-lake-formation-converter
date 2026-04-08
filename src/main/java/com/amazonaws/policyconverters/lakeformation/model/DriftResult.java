package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the result of a drift computation between Cedar-derived desired
 * permissions and actual LakeFormation permissions.
 *
 * <p>Contains a {@link DriftReport} summarizing the drift and a list of
 * corrective {@link LFPermissionOperation} objects needed to reconcile the two
 * states. The {@code correctiveOperations} list is empty when the drift
 * detector runs in report-only mode.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DriftResult {

    private final DriftReport report;
    private final List<LFPermissionOperation> correctiveOperations;

    @JsonCreator
    public DriftResult(
            @JsonProperty("report") DriftReport report,
            @JsonProperty("correctiveOperations") List<LFPermissionOperation> correctiveOperations) {
        this.report = report;
        this.correctiveOperations = correctiveOperations != null
                ? Collections.unmodifiableList(new ArrayList<>(correctiveOperations))
                : Collections.emptyList();
    }

    public DriftReport getReport() {
        return report;
    }

    public List<LFPermissionOperation> getCorrectiveOperations() {
        return correctiveOperations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DriftResult that = (DriftResult) o;
        return Objects.equals(report, that.report)
                && Objects.equals(correctiveOperations, that.correctiveOperations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(report, correctiveOperations);
    }

    @Override
    public String toString() {
        return "DriftResult{" +
                "report=" + report +
                ", correctiveOperations=" + correctiveOperations +
                '}';
    }
}
