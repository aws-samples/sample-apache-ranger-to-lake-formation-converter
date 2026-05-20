package com.amazonaws.policyconverters.model;

import java.util.Objects;

public class TagSyncResult {

    private final boolean success;
    private final long durationMs;
    private final int tagsCreated;
    private final int tagsDeleted;
    private final int attachmentsAdded;
    private final int attachmentsRemoved;
    private final int failed;
    private final String errorMessage;

    private TagSyncResult(boolean success, long durationMs, int tagsCreated, int tagsDeleted,
                          int attachmentsAdded, int attachmentsRemoved, int failed, String errorMessage) {
        this.success = success;
        this.durationMs = durationMs;
        this.tagsCreated = tagsCreated;
        this.tagsDeleted = tagsDeleted;
        this.attachmentsAdded = attachmentsAdded;
        this.attachmentsRemoved = attachmentsRemoved;
        this.failed = failed;
        this.errorMessage = errorMessage;
    }

    public static TagSyncResult success(long durationMs, int tagsCreated, int tagsDeleted,
                                        int attachmentsAdded, int attachmentsRemoved, int failed) {
        return new TagSyncResult(true, durationMs, tagsCreated, tagsDeleted,
                attachmentsAdded, attachmentsRemoved, failed, null);
    }

    public static TagSyncResult failure(long durationMs, Exception error) {
        return new TagSyncResult(false, durationMs, 0, 0, 0, 0, 0,
                error != null ? error.getMessage() : "unknown error");
    }

    public boolean isSuccess() {
        return success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getTagsCreated() {
        return tagsCreated;
    }

    public int getTagsDeleted() {
        return tagsDeleted;
    }

    public int getAttachmentsAdded() {
        return attachmentsAdded;
    }

    public int getAttachmentsRemoved() {
        return attachmentsRemoved;
    }

    public int getFailed() {
        return failed;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TagSyncResult that = (TagSyncResult) o;
        return success == that.success
                && durationMs == that.durationMs
                && tagsCreated == that.tagsCreated
                && tagsDeleted == that.tagsDeleted
                && attachmentsAdded == that.attachmentsAdded
                && attachmentsRemoved == that.attachmentsRemoved
                && failed == that.failed
                && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, durationMs, tagsCreated, tagsDeleted,
                attachmentsAdded, attachmentsRemoved, failed, errorMessage);
    }

    @Override
    public String toString() {
        return "TagSyncResult{" +
                "success=" + success +
                ", durationMs=" + durationMs +
                ", tagsCreated=" + tagsCreated +
                ", tagsDeleted=" + tagsDeleted +
                ", attachmentsAdded=" + attachmentsAdded +
                ", attachmentsRemoved=" + attachmentsRemoved +
                ", failed=" + failed +
                (errorMessage != null ? ", errorMessage='" + errorMessage + '\'' : "") +
                '}';
    }
}
