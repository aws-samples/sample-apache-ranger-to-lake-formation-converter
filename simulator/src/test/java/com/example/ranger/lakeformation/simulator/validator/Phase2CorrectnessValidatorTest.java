package com.example.ranger.lakeformation.simulator.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Phase2CorrectnessValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> PRINCIPALS = Map.of(
            "alice", "arn:aws:iam::123:role/alice"
    );

    private Phase2CorrectnessValidator validator;

    @BeforeEach
    void setUp() {
        ExpectedPermissionsComputer computer = new ExpectedPermissionsComputer(
                PRINCIPALS, (db, pattern) -> List.of(pattern));
        validator = new Phase2CorrectnessValidator(computer, Set.of());
    }

    // -----------------------------------------------------------------------
    // Helper builders (matching pattern from ExpectedPermissionsComputerTest)
    // -----------------------------------------------------------------------

    private JsonNode buildPolicy(JsonNode policyItems, JsonNode resources) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("id", "test-policy");
        p.put("isEnabled", true);
        p.put("service", "hive");
        p.put("policyType", 0);
        p.set("policyItems", policyItems);
        p.set("resources", resources);
        return p;
    }

    private JsonNode buildItem(String user, String accessType) {
        ObjectNode item = MAPPER.createObjectNode();
        ArrayNode users = MAPPER.createArrayNode();
        users.add(user);
        item.set("users", users);
        ArrayNode accesses = MAPPER.createArrayNode();
        ObjectNode access = MAPPER.createObjectNode();
        access.put("type", accessType);
        access.put("isAllowed", true);
        accesses.add(access);
        item.set("accesses", accesses);
        item.put("delegateAdmin", false);
        return item;
    }

    private JsonNode buildTableResources(String db, String table) {
        ObjectNode resources = MAPPER.createObjectNode();
        ObjectNode dbRes = MAPPER.createObjectNode();
        ArrayNode dbVals = MAPPER.createArrayNode();
        dbVals.add(db);
        dbRes.set("values", dbVals);
        resources.set("database", dbRes);
        ObjectNode tblRes = MAPPER.createObjectNode();
        ArrayNode tblVals = MAPPER.createArrayNode();
        tblVals.add(table);
        tblRes.set("values", tblVals);
        resources.set("table", tblRes);
        return resources;
    }

    private ArrayNode singleItemArray(JsonNode item) {
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(item);
        return arr;
    }

    // LF returns bare-table SELECT as TableWithColumns (cols=None) from ListPermissions
    private static final SimulatorPermission ALICE_SELECT_EVENTS = new SimulatorPermission(
            "arn:aws:iam::123:role/alice", "TABLE_WITH_COLUMNS", "mydb.events", "SELECT", false);

    // 1. actual equals expected → PASS
    @Test
    void actualEqualsExpectedReturnsPass() {
        JsonNode policy = buildPolicy(
                singleItemArray(buildItem("alice", "select")),
                buildTableResources("mydb", "events"));

        ValidationResult result = validator.validate(Set.of(ALICE_SELECT_EVENTS), List.of(policy));

        assertFalse(result.isViolation());
        assertEquals(ValidationResult.Outcome.PASS, result.outcome());
        assertTrue(result.overGrants().isEmpty());
        assertTrue(result.underGrants().isEmpty());
    }

    // 2. actual has extra permission → TRANSIENT_VIOLATION, correct over-grant in result
    @Test
    void actualHasExtraPermissionProducesTransientViolationWithOverGrant() {
        JsonNode policy = buildPolicy(
                singleItemArray(buildItem("alice", "select")),
                buildTableResources("mydb", "events"));

        SimulatorPermission extraPerm = new SimulatorPermission(
                "arn:aws:iam::123:role/alice", "TABLE", "mydb.orders", "INSERT", false);

        ValidationResult result = validator.validate(
                Set.of(ALICE_SELECT_EVENTS, extraPerm), List.of(policy));

        assertTrue(result.isViolation());
        assertEquals(ValidationResult.Outcome.TRANSIENT_VIOLATION, result.outcome());
        assertEquals(Set.of(extraPerm), result.overGrants());
        assertTrue(result.underGrants().isEmpty());
    }

    // 3. actual missing expected permission → TRANSIENT_VIOLATION, correct under-grant in result
    @Test
    void actualMissingExpectedPermissionProducesTransientViolationWithUnderGrant() {
        JsonNode policy = buildPolicy(
                singleItemArray(buildItem("alice", "select")),
                buildTableResources("mydb", "events"));

        ValidationResult result = validator.validate(Set.of(), List.of(policy));

        assertTrue(result.isViolation());
        assertEquals(ValidationResult.Outcome.TRANSIENT_VIOLATION, result.outcome());
        assertTrue(result.overGrants().isEmpty());
        assertEquals(Set.of(ALICE_SELECT_EVENTS), result.underGrants());
    }

    // 4. Empty policy list + empty actual → PASS
    @Test
    void emptyPoliciesAndEmptyActualReturnsPass() {
        ValidationResult result = validator.validate(Set.of(), List.of());

        assertFalse(result.isViolation());
        assertEquals(ValidationResult.Outcome.PASS, result.outcome());
        assertTrue(result.overGrants().isEmpty());
        assertTrue(result.underGrants().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Tests for managedPrincipalArns filtering branch
    // -----------------------------------------------------------------------

    // 5. Unmanaged principal permissions are stripped before comparison → PASS
    @Test
    void unmanagedPrincipalPermissionsAreFilteredBeforeComparison() {
        ExpectedPermissionsComputer computer = new ExpectedPermissionsComputer(
                PRINCIPALS, (db, pattern) -> List.of(pattern));
        Phase2CorrectnessValidator filteredValidator =
                new Phase2CorrectnessValidator(computer, Set.of("arn:aws:iam::123:role/alice"));

        JsonNode policy = buildPolicy(
                singleItemArray(buildItem("alice", "select")),
                buildTableResources("mydb", "events"));

        SimulatorPermission infraPerm = new SimulatorPermission(
                "arn:aws:iam::123:role/infra-admin", "TABLE", "mydb.events", "SELECT", false);

        // actual = alice's correct grant + infra-admin grant (unmanaged, should be filtered)
        ValidationResult result = filteredValidator.validate(
                Set.of(ALICE_SELECT_EVENTS, infraPerm), List.of(policy));

        assertFalse(result.isViolation());
        assertEquals(ValidationResult.Outcome.PASS, result.outcome());
        assertTrue(result.overGrants().isEmpty());
        assertTrue(result.underGrants().isEmpty());
    }

    // 6. Over-grant from managed principal is still detected even with unmanaged permissions present
    @Test
    void managedPrincipalOverGrantDetectedWhenOtherUnmanagedPermissionsPresent() {
        ExpectedPermissionsComputer computer = new ExpectedPermissionsComputer(
                PRINCIPALS, (db, pattern) -> List.of(pattern));
        Phase2CorrectnessValidator filteredValidator =
                new Phase2CorrectnessValidator(computer, Set.of("arn:aws:iam::123:role/alice"));

        JsonNode policy = buildPolicy(
                singleItemArray(buildItem("alice", "select")),
                buildTableResources("mydb", "events"));

        SimulatorPermission aliceInsert = new SimulatorPermission(
                "arn:aws:iam::123:role/alice", "TABLE", "mydb.events", "INSERT", false);
        SimulatorPermission infraPerm = new SimulatorPermission(
                "arn:aws:iam::123:role/infra-admin", "TABLE", "mydb.events", "SELECT", false);

        // actual = alice's correct grant + alice's over-grant (INSERT) + infra-admin (filtered out)
        ValidationResult result = filteredValidator.validate(
                Set.of(ALICE_SELECT_EVENTS, aliceInsert, infraPerm), List.of(policy));

        assertTrue(result.isViolation());
        assertEquals(ValidationResult.Outcome.TRANSIENT_VIOLATION, result.outcome());
        assertEquals(Set.of(aliceInsert), result.overGrants());
        assertTrue(result.underGrants().isEmpty());
    }

    // 7. Empty managedPrincipalArns skips filtering → unmanaged permission treated as over-grant
    @Test
    void emptyManagedSetSkipsFilteringAndExposesAllPermissions() {
        // validator field uses Set.of() — filtering branch is skipped
        JsonNode policy = buildPolicy(
                singleItemArray(buildItem("alice", "select")),
                buildTableResources("mydb", "events"));

        SimulatorPermission infraPerm = new SimulatorPermission(
                "arn:aws:iam::123:role/infra-admin", "TABLE", "mydb.events", "SELECT", false);

        // No filtering: infra-admin permission appears as an over-grant
        ValidationResult result = validator.validate(
                Set.of(ALICE_SELECT_EVENTS, infraPerm), List.of(policy));

        assertTrue(result.isViolation());
        assertEquals(ValidationResult.Outcome.TRANSIENT_VIOLATION, result.outcome());
        assertEquals(Set.of(infraPerm), result.overGrants());
        assertTrue(result.underGrants().isEmpty());
    }
}
