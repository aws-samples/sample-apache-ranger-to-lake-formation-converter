package com.amazonaws.policyconverters.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregated report of all policy gaps encountered during conversion.
 * Contains individual gap entries and a summary of counts per gap type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GapReport {

    private final List<GapEntry> entries;
    private final Map<GapEntry.GapType, Integer> summary;
    private final String generatedAt;

    @JsonCreator
    public GapReport(
            @JsonProperty("entries") List<GapEntry> entries,
            @JsonProperty("summary") Map<GapEntry.GapType, Integer> summary,
            @JsonProperty("generatedAt") String generatedAt) {
        this.entries = entries != null
                ? Collections.unmodifiableList(new ArrayList<>(entries))
                : Collections.<GapEntry>emptyList();
        this.summary = summary != null && !summary.isEmpty()
                ? Collections.unmodifiableMap(new EnumMap<>(summary))
                : Collections.<GapEntry.GapType, Integer>emptyMap();
        this.generatedAt = generatedAt;
    }

    /**
     * Compute a summary map from the entries list.
     * Counts the number of entries per GapType.
     *
     * @param entries the gap entries to summarize
     * @return map of GapType to count
     */
    public static Map<GapEntry.GapType, Integer> computeSummary(List<GapEntry> entries) {
        Map<GapEntry.GapType, Integer> counts = new EnumMap<>(GapEntry.GapType.class);
        if (entries != null) {
            for (GapEntry entry : entries) {
                GapEntry.GapType type = entry.getGapType();
                Integer current = counts.get(type);
                counts.put(type, current != null ? current + 1 : 1);
            }
        }
        return counts;
    }

    public List<GapEntry> getEntries() {
        return entries;
    }

    public Map<GapEntry.GapType, Integer> getSummary() {
        return summary;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GapReport gapReport = (GapReport) o;
        return Objects.equals(entries, gapReport.entries)
                && Objects.equals(summary, gapReport.summary)
                && Objects.equals(generatedAt, gapReport.generatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries, summary, generatedAt);
    }

    @Override
    public String toString() {
        return "GapReport{" +
                "entries=" + entries.size() + " entries" +
                ", summary=" + summary +
                ", generatedAt='" + generatedAt + '\'' +
                '}';
    }
}
