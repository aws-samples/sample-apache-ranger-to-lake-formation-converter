package com.example.ranger.lakeformation.simulator.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ExpectedPermissionsComputerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> PRINCIPAL_MAP = Map.of(
            "alice", "arn:aws:iam::123:role/alice",
            "analysts", "arn:aws:iam::123:role/analysts"
    );

    private ExpectedPermissionsComputer computer;

    @BeforeEach
    void setUp() {
        computer = new ExpectedPermissionsComputer(PRINCIPAL_MAP, (db, pattern) -> List.of(pattern));
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private JsonNode buildPolicy(boolean isEnabled, String service, int policyType,
            JsonNode policyItems, JsonNode resources) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("id", "test-policy-1");
        p.put("isEnabled", isEnabled);
        p.put("service", service);
        p.put("policyType", policyType);
        p.set("policyItems", policyItems);
        p.set("denyPolicyItems", MAPPER.createArrayNode());
        p.set("resources", resources);
        return p;
    }

    private JsonNode buildItem(String user, String accessType, boolean delegateAdmin) {
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
        item.put("delegateAdmin", delegateAdmin);
        return item;
    }

    private ObjectNode buildTableResources(String db, String table) {
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

    private JsonNode buildDatabaseResources(String db) {
        ObjectNode resources = MAPPER.createObjectNode();
        ObjectNode dbRes = MAPPER.createObjectNode();
        ArrayNode dbVals = MAPPER.createArrayNode();
        dbVals.add(db);
        dbRes.set("values", dbVals);
        resources.set("database", dbRes);
        return resources;
    }

    private JsonNode buildColumnResources(String db, String table, String column) {
        ObjectNode resources = buildTableResources(db, table);
        ObjectNode colRes = MAPPER.createObjectNode();
        ArrayNode colVals = MAPPER.createArrayNode();
        colVals.add(column);
        colRes.set("values", colVals);
        ((ObjectNode) resources).set("column", colRes);
        return resources;
    }

    private JsonNode buildDataLocationResources(String s3Path) {
        ObjectNode resources = MAPPER.createObjectNode();
        ObjectNode dlRes = MAPPER.createObjectNode();
        ArrayNode dlVals = MAPPER.createArrayNode();
        dlVals.add(s3Path);
        dlRes.set("values", dlVals);
        resources.set("datalocation", dlRes);
        return resources;
    }

    private ArrayNode singleItemArray(JsonNode item) {
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(item);
        return arr;
    }

    // -----------------------------------------------------------------------
    // Test 1: disabled policy produces no permissions
    // -----------------------------------------------------------------------

    @Test
    void disabledPolicyProducesNoPermissions() {
        JsonNode items = singleItemArray(buildItem("alice", "select", false));
        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(false, "hive", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertTrue(result.isEmpty(), "Expected no permissions for disabled policy");
    }

    // -----------------------------------------------------------------------
    // Test 2: tag service produces no permissions
    // -----------------------------------------------------------------------

    @Test
    void tagServiceProducesNoPermissions() {
        JsonNode items = singleItemArray(buildItem("alice", "select", false));
        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive_tag", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertTrue(result.isEmpty(), "Expected no permissions for tag-based service");
    }

    // -----------------------------------------------------------------------
    // Test 3: data masking policy produces no permissions
    // -----------------------------------------------------------------------

    @Test
    void dataMaskingPolicyProducesNoPermissions() {
        JsonNode items = singleItemArray(buildItem("alice", "select", false));
        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive", 1, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertTrue(result.isEmpty(), "Expected no permissions for data masking policy (policyType=1)");
    }

    // -----------------------------------------------------------------------
    // Test 4: deny items produce no grants
    // -----------------------------------------------------------------------

    @Test
    void denyItemsProduceNoGrants() {
        // Build policy with only denyPolicyItems, no policyItems array
        ObjectNode p = MAPPER.createObjectNode();
        p.put("id", "deny-policy");
        p.put("isEnabled", true);
        p.put("service", "hive");
        p.put("policyType", 0);
        // Empty policyItems
        p.set("policyItems", MAPPER.createArrayNode());
        // Populated denyPolicyItems
        ArrayNode denyItems = MAPPER.createArrayNode();
        denyItems.add(buildItem("alice", "select", false));
        p.set("denyPolicyItems", denyItems);
        p.set("resources", buildTableResources("mydb", "events"));

        Set<SimulatorPermission> result = computer.compute(List.of(p));

        assertTrue(result.isEmpty(), "Deny items must not produce any grants");
    }

    // -----------------------------------------------------------------------
    // Test 5: select permission on table
    // -----------------------------------------------------------------------

    @Test
    void selectPermissionOnTable() {
        JsonNode items = singleItemArray(buildItem("alice", "select", false));
        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("arn:aws:iam::123:role/alice", perm.principalArn());
        assertEquals("TABLE_WITH_COLUMNS", perm.resourceType(), "SELECT is stored as TABLE_WITH_COLUMNS in LF");
        assertEquals("mydb.events", perm.resourceId());
        assertEquals("SELECT", perm.permission());
        assertFalse(perm.grantable());
    }

    // -----------------------------------------------------------------------
    // Test 6: "all" access expands to 6 permissions
    // -----------------------------------------------------------------------

    @Test
    void allAccessExpandsToMultiplePermissions() {
        JsonNode items = singleItemArray(buildItem("alice", "all", false));
        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "lakeformation", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        Set<String> permissions = result.stream()
                .map(SimulatorPermission::permission)
                .collect(Collectors.toSet());
        assertEquals(Set.of("SELECT", "INSERT", "DELETE", "ALTER", "DROP", "DESCRIBE"), permissions,
                "all access should expand to 6 LF permissions");
        assertEquals(6, result.size(), "Should have exactly 6 SimulatorPermission entries");
    }

    // -----------------------------------------------------------------------
    // Test 7: delegateAdmin produces grantable=true
    // -----------------------------------------------------------------------

    @Test
    void delegateAdminProducesGrantable() {
        JsonNode items = singleItemArray(buildItem("alice", "select", true));
        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertEquals(1, result.size());
        assertTrue(result.iterator().next().grantable(), "delegateAdmin=true should set grantable=true");
    }

    // -----------------------------------------------------------------------
    // Test 8: unmapped principal produces no permissions
    // -----------------------------------------------------------------------

    @Test
    void unmappedPrincipalProducesNoPermissions() {
        JsonNode items = singleItemArray(buildItem("bob", "select", false));
        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertTrue(result.isEmpty(), "bob is not in principal map, so no permissions expected");
    }

    // -----------------------------------------------------------------------
    // Test 9: database-level permission
    // -----------------------------------------------------------------------

    @Test
    void databaseLevelPermission() {
        JsonNode items = singleItemArray(buildItem("alice", "create_database", false));
        JsonNode resources = buildDatabaseResources("mydb");
        JsonNode policy = buildPolicy(true, "lakeformation", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("DATABASE", perm.resourceType());
        assertEquals("mydb", perm.resourceId());
        assertEquals("CREATE_DATABASE", perm.permission());
    }

    // -----------------------------------------------------------------------
    // Test 10: wildcard table produces db.*
    // -----------------------------------------------------------------------

    @Test
    void wildcardTableProducesDbDotStar() {
        JsonNode items = singleItemArray(buildItem("alice", "select", false));
        JsonNode resources = buildTableResources("mydb", "*");
        JsonNode policy = buildPolicy(true, "hive", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("TABLE_WITH_COLUMNS", perm.resourceType(), "SELECT is stored as TABLE_WITH_COLUMNS in LF");
        assertEquals("mydb.*", perm.resourceId(),
                "Bare wildcard table pattern '*' should produce resourceId 'mydb.*'");
    }

    // -----------------------------------------------------------------------
    // Test 11: column-level grant produces only SELECT
    // LF TABLE_WITH_COLUMNS only supports SELECT — all other permissions (INSERT, DELETE,
    // ALTER, DROP, DESCRIBE) are ignored by the sync service for column-scoped resources.
    // -----------------------------------------------------------------------

    @Test
    void columnLevelGrantProducesOnlySelect() {
        JsonNode items = singleItemArray(buildItem("alice", "all", false));
        JsonNode resources = buildColumnResources("mydb", "events", "salary");
        JsonNode policy = buildPolicy(true, "lakeformation", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertEquals(1, result.size(), "column-level 'all' should produce exactly 1 permission (SELECT)");
        SimulatorPermission perm = result.iterator().next();
        assertEquals("TABLE_WITH_COLUMNS", perm.resourceType());
        assertEquals("SELECT", perm.permission(), "only SELECT is valid for TABLE_WITH_COLUMNS");
    }

    // -----------------------------------------------------------------------
    // Test 12: data location permission
    // -----------------------------------------------------------------------

    @Test
    void dataLocationPermission() {
        JsonNode items = singleItemArray(buildItem("alice", "datalocation", false));
        JsonNode resources = buildDataLocationResources("s3://my-bucket/path/");
        JsonNode policy = buildPolicy(true, "lakeformation", 0, items, resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("DATA_LOCATION", perm.resourceType());
        assertEquals("s3://my-bucket/path/", perm.resourceId());
        assertEquals("DATA_LOCATION_ACCESS", perm.permission());
    }

    // -----------------------------------------------------------------------
    // Hive service tests
    // -----------------------------------------------------------------------

    @Test
    void hiveSelect_mapsToSelectPermission() {
        String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertFalse(result.isEmpty(), "hive select should produce a permission");
        assertTrue(result.stream().anyMatch(p -> "SELECT".equals(p.permission())),
                "hive select must map to SELECT");
    }

    @Test
    void hiveAll_producesNoPermissions() {
        // "all" in the "hive" service map is absent (HiveServiceAdapter maps it to "SUPER" → no LF permission).
        // Note: the simulator's generateAllAccessTablePolicy() uses rangerServiceName ("lakeformation"), not "hive",
        // so that generator takes the lakeformation map where "all" expands to 6 permissions. This test covers
        // the "hive"-named service path, which is reachable if an operator configures a custom hive service name.
        String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"all\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertTrue(result.isEmpty(),
                "hive 'all' maps to SUPER (not an LF permission) — must produce zero grants");
    }

    @Test
    void hiveUpdate_mapsToInsertPermission() {
        String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"update\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertTrue(result.stream().anyMatch(p -> "INSERT".equals(p.permission())),
                "hive 'update' must map to INSERT");
    }

    // -----------------------------------------------------------------------
    // Trino service tests
    // -----------------------------------------------------------------------

    @Test
    void trinoUse_mapsToDescribePermission() {
        String json = "{\"service\":\"trino\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"schema\":{\"values\":[\"mydb\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"use\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertTrue(result.stream().anyMatch(p -> "DESCRIBE".equals(p.permission())),
                "trino 'use' must map to DESCRIBE");
    }

    @Test
    void trinoSchemaKey_resolvesSameAsDatabaseKey() {
        // Trino uses "schema" resource key; ExpectedPermissionsComputer must treat it as the database name
        String json = "{\"service\":\"trino\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"schema\":{\"values\":[\"analytics\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"events\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertFalse(result.isEmpty(), "Trino schema key should produce permissions");
        assertTrue(result.stream().anyMatch(p -> p.resourceId().startsWith("analytics.")),
                "Resource id should use the schema name as the database component");
    }

    // -----------------------------------------------------------------------
    // Cross-service forbid test
    // -----------------------------------------------------------------------

    @Test
    void crossServiceForbid_trinoDeniesSuppressesHiveGrant() {
        // Policy A: Hive grants alice SELECT on db1.t1
        // Policy B: Trino denies alice SELECT on db1.t1 (same logical resource, different service)
        // Expected: compute([A, B]) returns empty set — deny wins
        String policyA = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        String policyB = "{\"service\":\"trino\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"schema\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[],"
                + "\"denyPolicyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}]}";
        Set<SimulatorPermission> result = computeFromJsonStrings(policyA, policyB);
        // Cross-service deny is intentional: this validator uses a single logical resource space.
        // DenyKey omits resourceType so that TABLE-based denies suppress TABLE_WITH_COLUMNS permits
        // for the same (principal, resourceId, permission) — which is the correct LF semantic.
        assertTrue(result.isEmpty(),
                "Trino deny must suppress Hive grant for same (principal, resource, permission)");
    }

    // -----------------------------------------------------------------------
    // Private helpers for the new tests
    // -----------------------------------------------------------------------

    // Single-policy convenience helper used by the new hive/trino tests
    private Set<SimulatorPermission> compute(String json) {
        try {
            JsonNode policy = MAPPER.readTree(json);
            return computer.compute(List.of(policy));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Multi-policy helper used by the cross-service forbid test
    private Set<SimulatorPermission> computeFromJsonStrings(String... jsonPolicies) {
        try {
            List<JsonNode> nodes = new ArrayList<>();
            for (String json : jsonPolicies) {
                nodes.add(MAPPER.readTree(json));
            }
            return computer.compute(nodes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
