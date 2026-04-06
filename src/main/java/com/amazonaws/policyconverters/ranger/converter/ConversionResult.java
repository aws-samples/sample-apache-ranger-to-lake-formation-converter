package com.amazonaws.policyconverters.ranger.converter;

import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the result of a batch policy conversion.
 * Contains the generated LF permission operations along with
 * counts of successfully converted and skipped (malformed) policies.
 */
public class ConversionResult {

    private final List<LFPermissionOperation> operations;
    private final int successCount;
    private final int skippedCount;

    public ConversionResult(List<LFPermissionOperation> operations, int successCount, int skippedCount) {
        this.operations = operations != null
                ? Collections.unmodifiableList(new ArrayList<>(operations))
                : Collections.<LFPermissionOperation>emptyList();
        this.successCount = successCount;
        this.skippedCount = skippedCount;
    }

    public List<LFPermissionOperation> getOperations() {
        return operations;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    @Override
    public String toString() {
        return "ConversionResult{" +
                "operations=" + operations.size() +
                ", successCount=" + successCount +
                ", skippedCount=" + skippedCount +
                '}';
    }
}
