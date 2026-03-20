package org.apache.ranger.lakeformation.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.lakeformation.model.GapEntry;
import org.apache.ranger.lakeformation.model.GapReport;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Collects and reports unsupported Ranger policy features that cannot be
 * represented in AWS Lake Formation. Supports JSON serialization and
 * deserialization for persistence and transport.
 */
public class GapReporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<GapEntry> entries;

    public GapReporter() {
        this.entries = new ArrayList<>();
    }

    /**
     * Record an unsupported feature encountered during conversion.
     *
     * @param entry the gap entry to record
     */
    public void recordGap(GapEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("GapEntry must not be null");
        }
        entries.add(entry);
    }

    /**
     * Get the complete gap report with computed summary and current timestamp.
     *
     * @return a GapReport containing all recorded entries, summary counts per
     *         GapType, and an ISO-8601 generated-at timestamp
     */
    public GapReport getReport() {
        Map<GapEntry.GapType, Integer> summary = GapReport.computeSummary(entries);
        String generatedAt = formatIso8601(new Date());
        return new GapReport(
                Collections.unmodifiableList(new ArrayList<>(entries)),
                summary,
                generatedAt
        );
    }

    /**
     * Serialize the current gap report to a JSON string.
     *
     * @return JSON representation of the current report
     * @throws IOException if serialization fails
     */
    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(getReport());
    }

    /**
     * Deserialize a GapReport from a JSON string.
     *
     * @param json the JSON string to deserialize
     * @return the deserialized GapReport
     * @throws IOException if deserialization fails
     */
    public static GapReport fromJson(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON string must not be null or empty");
        }
        return MAPPER.readValue(json, GapReport.class);
    }

    static String formatIso8601(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }
}
