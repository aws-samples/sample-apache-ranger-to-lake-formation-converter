package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.validator.SimulatorPermission;
import com.example.ranger.lakeformation.simulator.workload.MutationLog;
import com.example.ranger.lakeformation.simulator.workload.MutationOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoundReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private RoundReporter reporter;
    private MutationDriver driver;
    private Path reportPath;

    @BeforeEach
    void setUp() throws IOException {
        reportPath = tmp.resolve("round-report.txt");
        reporter = new RoundReporter(reportPath);
        // Driver is only used to resolve internal→Ranger IDs; a real one with an empty log is fine.
        driver = new MutationDriver(null, new MutationLog(tmp.resolve("mutation.log")));
    }

    private static SimulatorPermission perm(String arn, String type, String id, String action, boolean grantable) {
        return new SimulatorPermission(arn, type, id, action, grantable);
    }

    private static JsonNode policy(long id, String service, boolean enabled) throws IOException {
        String json = "{"
                + "\"id\":" + id + ","
                + "\"service\":\"" + service + "\","
                + "\"isEnabled\":" + enabled + ","
                + "\"resources\":{\"database\":{\"values\":[\"db\"]},\"table\":{\"values\":[\"t" + id + "\"]}},"
                + "\"policyItems\":[{\"users\":[\"analyst\"],\"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]}]"
                + "}";
        return MAPPER.readTree(json);
    }

    @Test
    void rangerStateSectionExcludesDisabledPolicies() throws IOException {
        JsonNode enabled = policy(1, "lakeformation", true);
        JsonNode disabled = policy(2, "lakeformation", false);

        reporter.appendRound(0, List.of(), driver, List.of(enabled, disabled),
                Set.of(), Set.of());

        String report = Files.readString(reportPath);
        assertTrue(report.contains("Ranger State (enabled policies):"), "section header present");
        assertTrue(report.contains("Policy Id 1:"), "enabled policy 1 shown");
        assertFalse(report.contains("Policy Id 2:"), "disabled policy 2 excluded");
    }

    @Test
    void rangerStateSectionAppearsBeforeLfState() throws IOException {
        reporter.appendRound(0, List.of(), driver, List.of(policy(1, "lakeformation", true)),
                Set.of(), Set.of());

        String report = Files.readString(reportPath);
        int rangerIdx = report.indexOf("Ranger State (enabled policies):");
        int lfIdx = report.indexOf("Current LF State:");
        assertTrue(rangerIdx >= 0 && lfIdx >= 0, "both sections present");
        assertTrue(rangerIdx < lfIdx, "Ranger State must appear before Current LF State");
    }

    @Test
    void emptyRangerPoliciesRendersPlaceholder() throws IOException {
        reporter.appendRound(0, List.of(), driver, List.of(), Set.of(), Set.of());

        String report = Files.readString(reportPath);
        assertTrue(report.contains("- (no enabled Ranger policies)"),
                "empty Ranger state renders a placeholder line");
    }

    @Test
    void lfGrantsGroupMultipleActionsOnOneLine() throws IOException {
        // Two single-action permissions for the same (principal, resource) must render as one line.
        String arn = "arn:aws:iam::123:role/ranger-sim-analyst";
        Set<SimulatorPermission> current = Set.of(
                perm(arn, "TABLE", "db.t", "ALTER", false),
                perm(arn, "TABLE", "db.t", "INSERT", false));

        reporter.appendRound(0, List.of(), driver, List.of(), Set.of(), current);

        String report = Files.readString(reportPath);
        assertTrue(report.contains("Actions: ALTER, INSERT"),
                "actions for the same principal/resource must be grouped on one line");
    }

    @Test
    void mutationSectionListsAppliedBatch() throws IOException {
        MutationOperation.CreatePolicy create = new MutationOperation.CreatePolicy(
                Instant.EPOCH, "sim-1",
                MAPPER.convertValue(policy(9, "hive", true),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));

        reporter.appendRound(3, List.of(create), driver, List.of(), Set.of(), Set.of());

        String report = Files.readString(reportPath);
        assertTrue(report.contains("Round 3:"), "round header present");
        assertTrue(report.contains("Ranger Policies Created:"), "mutation section present");
        assertTrue(report.contains("Grant"), "create mutation summarized as a Grant");
    }
}
