package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void getWarnings_nullConstructorArg_returnsEmptyList() {
        AssessmentResult result = result(null);
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void getWarnings_emptyList_returnsEmptyList() {
        AssessmentResult result = result(List.of());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void serialise_withWarnings_includesWarningsKey() throws Exception {
        AssessmentResult result = result(List.of("test warning"));
        String json = MAPPER.writeValueAsString(result);
        assertTrue(json.contains("\"warnings\""), "warnings key must appear in JSON when non-empty");
        assertTrue(json.contains("test warning"));
    }

    @Test
    void serialise_emptyWarnings_omitsWarningsKey() throws Exception {
        AssessmentResult result = result(List.of());
        String json = MAPPER.writeValueAsString(result);
        assertFalse(json.contains("\"warnings\""), "warnings key must be absent from JSON when empty");
    }

    @Test
    void deserialise_oldJsonWithoutWarnings_returnsEmptyList() throws Exception {
        String oldJson = "{\"totalPolicies\":0,\"fullyConvertible\":0,\"partiallyConvertible\":0,"
                + "\"notConvertible\":0,\"projectedGrantCount\":0,"
                + "\"gapReport\":{\"entries\":[],\"summary\":{},\"generatedAt\":\"2024-01-01T00:00:00Z\"},"
                + "\"source\":\"test\",\"services\":[]}";
        AssessmentResult result = MAPPER.readValue(oldJson, AssessmentResult.class);
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().isEmpty(), "Old JSON without warnings field must deserialise to empty list");
    }

    // ---- helper ----

    private AssessmentResult result(List<String> warnings) {
        GapReport gapReport = new GapReport(
                Collections.emptyList(),
                Collections.emptyMap(),
                "2024-01-01T00:00:00Z");
        return new AssessmentResult(0, 0, 0, 0, 0, gapReport, "test", List.of(), warnings);
    }
}
