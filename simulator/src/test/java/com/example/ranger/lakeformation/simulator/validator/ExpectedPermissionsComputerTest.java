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

    private JsonNode buildPolicyWithId(long id, boolean isEnabled, String service, int policyType,
            JsonNode policyItems, JsonNode resources) {
        ObjectNode p = (ObjectNode) buildPolicy(isEnabled, service, policyType, policyItems, resources);
        p.put("id", id);
        return p;
    }

    private JsonNode buildMultiAccessItem(String user, List<String> accessTypes, boolean delegateAdmin) {
        ObjectNode item = MAPPER.createObjectNode();
        ArrayNode users = MAPPER.createArrayNode();
        users.add(user);
        item.set("users", users);
        ArrayNode accesses = MAPPER.createArrayNode();
        for (String accessType : accessTypes) {
            ObjectNode access = MAPPER.createObjectNode();
            access.put("type", accessType);
            access.put("isAllowed", true);
            accesses.add(access);
        }
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

    /**
     * Regression: a bare-table SELECT must NOT be treated as a real column-restricted
     * TABLE_WITH_COLUMNS grant for the purpose of TABLE/TWC conflict resolution.
     * <p>
     * Lake Formation stores a bare-table SELECT as a TableWithColumns (all-columns) resource, and
     * the expected set mirrors that for comparison parity. But in the production sync service the
     * TABLE/TWC conflict check keys on actual column presence ({@code columnNames}), which a
     * bare-table SELECT lacks — so a bare-table SELECT and other bare-table actions from sibling
     * policies are all TABLE-class and never conflict. A genuine conflict only arises from a
     * real column-level Ranger policy.
     * <p>
     * Scenario mirrors the simulator finding: policy 148 grants ALTER (table) and policy 150 grants
     * {SELECT, DELETE, DROP} (table) to the same principal on the same table. The expected set must
     * retain SELECT — the lower-id ALTER policy must not phantom-conflict the SELECT away.
     */
    @Test
    void bareTableSelectIsNotStrippedByPhantomTableTwcConflict() {
        // Policy 148: ALTER on mydb.events (table-level). Lower id, so under the buggy logic its
        // TABLE class "wins" and strips the higher-id SELECT. Uses lakeformation service so the
        // access types map directly (mirrors the real finding: both policies were lakeformation).
        JsonNode policy148 = buildPolicyWithId(148, true, "lakeformation", 0,
                singleItemArray(buildItem("alice", "alter", false)),
                buildTableResources("mydb", "events"));
        // Policy 150: SELECT, DELETE, DROP on mydb.events (table-level)
        JsonNode policy150 = buildPolicyWithId(150, true, "lakeformation", 0,
                singleItemArray(buildMultiAccessItem("alice", List.of("select", "delete", "drop"), false)),
                buildTableResources("mydb", "events"));

        Set<SimulatorPermission> result = computer.compute(List.of(policy148, policy150));

        Set<String> perms = result.stream()
                .filter(p -> p.resourceId().equals("mydb.events"))
                .map(SimulatorPermission::permission)
                .collect(Collectors.toSet());
        assertTrue(perms.contains("SELECT"),
                "Bare-table SELECT must survive — no real column grant exists, so there is no "
                        + "TABLE/TWC conflict. Got: " + perms);
        assertEquals(Set.of("SELECT", "DELETE", "DROP", "ALTER"), perms,
                "All table-level permissions from both policies should be present");
    }

    /**
     * Complement to {@link #bareTableSelectIsNotStrippedByPhantomTableTwcConflict}: a GENUINE
     * TABLE/TWC conflict (a real column-level grant alongside a table-level grant from a different
     * policy) must still be resolved — the lower-id policy wins. This guards against the
     * bare-table-SELECT fix accidentally disabling legitimate conflict detection.
     */
    @Test
    void genuineColumnVsTableConflictStillResolves() {
        // Policy 200: table-level SELECT,DROP on mydb.events (lower id → TABLE wins)
        JsonNode tablePolicy = buildPolicyWithId(200, true, "lakeformation", 0,
                singleItemArray(buildMultiAccessItem("alice", List.of("select", "drop"), false)),
                buildTableResources("mydb", "events"));
        // Policy 300: genuine COLUMN-level SELECT on mydb.events (higher id → TWC loses)
        JsonNode columnPolicy = buildPolicyWithId(300, true, "lakeformation", 0,
                singleItemArray(buildItem("alice", "select", false)),
                buildColumnResources("mydb", "events", "salary"));

        Set<SimulatorPermission> result = computer.compute(List.of(tablePolicy, columnPolicy));

        // TABLE wins (id 200 < 300): the genuine column-restricted TWC SELECT is removed.
        // The bare-table SELECT from the winning policy remains (reported as TABLE_WITH_COLUMNS).
        Set<String> twcPerms = result.stream()
                .filter(p -> p.resourceType().equals("TABLE_WITH_COLUMNS"))
                .map(SimulatorPermission::permission)
                .collect(Collectors.toSet());
        Set<String> tablePerms = result.stream()
                .filter(p -> p.resourceType().equals("TABLE"))
                .map(SimulatorPermission::permission)
                .collect(Collectors.toSet());
        // The losing column grant is gone; winning table policy's DROP remains, plus its
        // bare-table SELECT (displayed as TWC). There must be exactly one SELECT (from the winner),
        // not a separate surviving column grant.
        assertTrue(tablePerms.contains("DROP"), "Winning table policy DROP must remain");
        assertEquals(Set.of("SELECT"), twcPerms,
                "Only the winning policy's bare-table SELECT remains (column grant removed)");
        long selectCount = result.stream().filter(p -> p.permission().equals("SELECT")).count();
        assertEquals(1, selectCount, "Exactly one SELECT entry — the losing column grant was removed");
    }

    /**
     * A column resource of {@code ["*"]} on a CONCRETE table means "all columns" = the whole table.
     * Production ({@code RangerToCedarConverter.promoteResourceLevel}) promotes this from column to
     * TABLE level, so non-SELECT actions (alter/write/...) produce full TABLE-level grants — NOT a
     * TABLE_WITH_COLUMNS SELECT-only grant. The oracle must mirror that, otherwise these table grants
     * are falsely flagged as over-grants.
     */
    @Test
    void columnWildcardOnConcreteTable_promotesToTableLevel_fullActions() {
        // emr-spark policy: column=* on mydb.orders, actions alter+write → TABLE ALTER + INSERT.
        JsonNode resources = buildColumnResources("mydb", "orders", "*");
        JsonNode policy = buildPolicy(true, "amazon-emr-spark", 0,
                singleItemArray(buildMultiAccessItem("alice", List.of("alter", "write"), false)),
                resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        Set<String> perms = result.stream().map(SimulatorPermission::permission).collect(Collectors.toSet());
        Set<String> types = result.stream().map(SimulatorPermission::resourceType).collect(Collectors.toSet());
        assertEquals(Set.of("ALTER", "INSERT"), perms,
                "column=* on a concrete table must grant full TABLE actions, not be stripped to SELECT. Got: " + perms);
        assertEquals(Set.of("TABLE"), types,
                "column=* on a concrete table is promoted to TABLE level. Got: " + types);
    }

    /**
     * A GENUINE specific-column resource (e.g. column=["salary"]) stays TABLE_WITH_COLUMNS and is
     * still stripped to SELECT-only — the promotion only applies to the column=* wildcard case.
     */
    @Test
    void genuineSpecificColumn_staysTwcSelectOnly() {
        JsonNode resources = buildColumnResources("mydb", "orders", "salary");
        JsonNode policy = buildPolicy(true, "amazon-emr-spark", 0,
                singleItemArray(buildMultiAccessItem("alice", List.of("alter", "write", "select"), false)),
                resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        Set<String> perms = result.stream().map(SimulatorPermission::permission).collect(Collectors.toSet());
        Set<String> types = result.stream().map(SimulatorPermission::resourceType).collect(Collectors.toSet());
        assertEquals(Set.of("SELECT"), perms,
                "A specific-column grant only supports SELECT in LF. Got: " + perms);
        assertEquals(Set.of("TABLE_WITH_COLUMNS"), types,
                "A specific-column grant stays TABLE_WITH_COLUMNS. Got: " + types);
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
    // Gap A — groups and roles principals
    // -----------------------------------------------------------------------

    @Test
    void groupPrincipalMappedToIamArn() {
        ExpectedPermissionsComputer c = new ExpectedPermissionsComputer(
                Map.of("data_team", "arn:aws:iam::123:role/data_team"),
                (db, pattern) -> List.of(pattern));

        ObjectNode item = MAPPER.createObjectNode();
        item.set("users", MAPPER.createArrayNode());
        ArrayNode groups = MAPPER.createArrayNode();
        groups.add("data_team");
        item.set("groups", groups);
        item.set("roles", MAPPER.createArrayNode());
        ArrayNode accesses = MAPPER.createArrayNode();
        ObjectNode access = MAPPER.createObjectNode();
        access.put("type", "select");
        access.put("isAllowed", true);
        accesses.add(access);
        item.set("accesses", accesses);
        item.put("delegateAdmin", false);

        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive", 0, singleItemArray(item), resources);

        Set<SimulatorPermission> result = c.compute(List.of(policy));

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("arn:aws:iam::123:role/data_team", perm.principalArn(),
                "group name should be mapped to its IAM ARN");
        assertEquals("SELECT", perm.permission());
    }

    @Test
    void rolePrincipalMappedToIamArn() {
        ExpectedPermissionsComputer c = new ExpectedPermissionsComputer(
                Map.of("etl_role", "arn:aws:iam::123:role/etl_role"),
                (db, pattern) -> List.of(pattern));

        ObjectNode item = MAPPER.createObjectNode();
        item.set("users", MAPPER.createArrayNode());
        item.set("groups", MAPPER.createArrayNode());
        ArrayNode roles = MAPPER.createArrayNode();
        roles.add("etl_role");
        item.set("roles", roles);
        ArrayNode accesses = MAPPER.createArrayNode();
        ObjectNode access = MAPPER.createObjectNode();
        access.put("type", "select");
        access.put("isAllowed", true);
        accesses.add(access);
        item.set("accesses", accesses);
        item.put("delegateAdmin", false);

        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive", 0, singleItemArray(item), resources);

        Set<SimulatorPermission> result = c.compute(List.of(policy));

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("arn:aws:iam::123:role/etl_role", perm.principalArn(),
                "role name should be mapped to its IAM ARN");
        assertEquals("SELECT", perm.permission());
    }

    // -----------------------------------------------------------------------
    // Gap B — isAllowed=false access entry is ignored
    // -----------------------------------------------------------------------

    @Test
    void accessWithIsAllowedFalseIsIgnored() {
        ObjectNode item = MAPPER.createObjectNode();
        ArrayNode users = MAPPER.createArrayNode();
        users.add("alice");
        item.set("users", users);
        item.set("groups", MAPPER.createArrayNode());
        item.set("roles", MAPPER.createArrayNode());
        ArrayNode accesses = MAPPER.createArrayNode();
        ObjectNode access = MAPPER.createObjectNode();
        access.put("type", "select");
        access.put("isAllowed", false);
        accesses.add(access);
        item.set("accesses", accesses);
        item.put("delegateAdmin", false);

        JsonNode resources = buildTableResources("mydb", "events");
        JsonNode policy = buildPolicy(true, "hive", 0, singleItemArray(item), resources);

        Set<SimulatorPermission> result = computer.compute(List.of(policy));

        assertTrue(result.isEmpty(),
                "access entry with isAllowed=false must produce no permissions");
    }

    // -----------------------------------------------------------------------
    // Gap C — Hive access type mappings: create, drop, alter
    // -----------------------------------------------------------------------

    @Test
    void hiveCreateAccessTypeProducesCreateTablePermission() {
        String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"create\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";

        Set<SimulatorPermission> result = compute(json);

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("CREATE_TABLE", perm.permission(),
                "hive 'create' must map to CREATE_TABLE");
        assertEquals("TABLE", perm.resourceType(),
                "CREATE_TABLE is a non-SELECT permission — resource type stays TABLE");
    }

    @Test
    void hiveDropAccessTypeProducesDropPermission() {
        String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"drop\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";

        Set<SimulatorPermission> result = compute(json);

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("DROP", perm.permission(),
                "hive 'drop' must map to DROP");
        assertEquals("TABLE", perm.resourceType(),
                "DROP is a non-SELECT permission — resource type stays TABLE");
    }

    @Test
    void hiveAlterAccessTypeProducesAlterPermission() {
        String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"alter\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";

        Set<SimulatorPermission> result = compute(json);

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("ALTER", perm.permission(),
                "hive 'alter' must map to ALTER");
        assertEquals("TABLE", perm.resourceType(),
                "ALTER is a non-SELECT permission — resource type stays TABLE");
    }

    // -----------------------------------------------------------------------
    // Gap D — data_location_access access type on lakeformation service
    // -----------------------------------------------------------------------

    @Test
    void dataLocationAccessTypeProducesDataLocationPermission() {
        String json = "{\"service\":\"lakeformation\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"datalocation\":{\"values\":[\"s3://bucket/\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"data_location_access\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";

        Set<SimulatorPermission> result = compute(json);

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals("DATA_LOCATION", perm.resourceType(),
                "data_location_access on a datalocation resource must produce DATA_LOCATION resource type");
        assertEquals("s3://bucket/", perm.resourceId());
        assertEquals("DATA_LOCATION_ACCESS", perm.permission(),
                "data_location_access access type must map to DATA_LOCATION_ACCESS permission");
    }

    // -----------------------------------------------------------------------
    // EMR Spark service tests
    // -----------------------------------------------------------------------

    @Test
    void emrSparkSelect_mapsToSelectPermission() {
        String json = "{\"service\":\"amazon-emr-spark\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertFalse(result.isEmpty(), "emr-spark select should produce a permission");
        assertTrue(result.stream().anyMatch(p -> "SELECT".equals(p.permission())),
                "emr-spark select must map to SELECT");
        assertEquals("TABLE_WITH_COLUMNS", result.iterator().next().resourceType(),
                "SELECT on a table must be TABLE_WITH_COLUMNS");
    }

    @Test
    void emrSparkUpdate_mapsToInsertPermission() {
        String json = "{\"service\":\"amazon-emr-spark\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"update\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertTrue(result.stream().anyMatch(p -> "INSERT".equals(p.permission())),
                "emr-spark 'update' must map to INSERT");
    }

    @Test
    void emrSparkRead_mapsToSelectPermission() {
        String json = "{\"service\":\"amazon-emr-spark\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"read\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertTrue(result.stream().anyMatch(p -> "SELECT".equals(p.permission())),
                "emr-spark 'read' must map to SELECT");
    }

    @Test
    void emrSparkWrite_mapsToInsertPermission() {
        String json = "{\"service\":\"amazon-emr-spark\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"write\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        Set<SimulatorPermission> result = compute(json);
        assertTrue(result.stream().anyMatch(p -> "INSERT".equals(p.permission())),
                "emr-spark 'write' must map to INSERT");
    }

    @Test
    void emrSparkDeny_suppressesGrant() {
        // EMR Spark deny policy must suppress the allow grant from the same service
        String policyAllow = "{\"service\":\"amazon-emr-spark\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}],"
                + "\"denyPolicyItems\":[]}";
        String policyDeny = "{\"service\":\"amazon-emr-spark\",\"isEnabled\":true,\"policyType\":0,"
                + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
                + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
                + "\"policyItems\":[],"
                + "\"denyPolicyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
                + "  \"delegateAdmin\":false}]}";
        Set<SimulatorPermission> result = computeFromJsonStrings(policyAllow, policyDeny);
        assertTrue(result.isEmpty(),
                "EMR Spark deny must suppress grant for same (principal, resource, permission)");
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
