package com.amazonaws.policyconverters.ranger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the ranger-servicedef-lakeformation.json structure against
 * Requirements 5.1, 5.2, and 5.3.
 */
class ServiceDefinitionJsonValidationTest {

    private static JsonNode serviceDef;

    @BeforeAll
    static void loadServiceDefinition() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = ServiceDefinitionJsonValidationTest.class
                .getClassLoader()
                .getResourceAsStream("ranger-servicedef-lakeformation.json")) {
            assertNotNull(is, "Service definition JSON not found on classpath");
            serviceDef = mapper.readTree(is);
        }
    }

    // --- Requirement 5.1: service type name and resource hierarchy ---

    @Test
    void serviceName_isLakeformation() {
        assertEquals("lakeformation", serviceDef.get("name").asText());
    }

    @Test
    void resources_containDatabaseTableColumn() {
        JsonNode resources = serviceDef.get("resources");
        assertNotNull(resources);
        assertTrue(resources.isArray());

        Set<String> names = new HashSet<>();
        for (JsonNode r : resources) {
            names.add(r.get("name").asText());
        }
        assertTrue(names.contains("database"), "Missing 'database' resource");
        assertTrue(names.contains("table"), "Missing 'table' resource");
        assertTrue(names.contains("column"), "Missing 'column' resource");
    }

    @Test
    void resources_hierarchyIsCorrect() {
        JsonNode resources = serviceDef.get("resources");

        // database has no parent, table's parent is database, column's parent is table
        for (JsonNode r : resources) {
            String name = r.get("name").asText();
            switch (name) {
                case "database":
                    assertFalse(r.has("parent"), "database should have no parent");
                    break;
                case "table":
                    assertEquals("database", r.get("parent").asText());
                    break;
                case "column":
                    assertEquals("table", r.get("parent").asText());
                    break;
            }
        }
    }

    @Test
    void resources_levelOrderIsCorrect() {
        JsonNode resources = serviceDef.get("resources");
        int dbLevel = -1, tableLevel = -1, colLevel = -1;

        for (JsonNode r : resources) {
            String name = r.get("name").asText();
            if ("database".equals(name)) dbLevel = r.get("level").asInt();
            if ("table".equals(name)) tableLevel = r.get("level").asInt();
            if ("column".equals(name)) colLevel = r.get("level").asInt();
        }

        assertTrue(dbLevel < tableLevel, "database level should be less than table level");
        assertTrue(tableLevel < colLevel, "table level should be less than column level");
    }

    @Test
    void resources_lookupSupportedOnAll() {
        for (JsonNode r : serviceDef.get("resources")) {
            String name = r.get("name").asText();
            // datalocation (S3 paths) does not support Ranger resource lookup
            if ("datalocation".equals(name)) {
                assertFalse(r.get("lookupSupported").asBoolean(),
                        "datalocation should not support lookup");
                continue;
            }
            assertTrue(r.get("lookupSupported").asBoolean(),
                    name + " should support lookup");
        }
    }

    // --- Requirement 5.2: access types matching LF permissions ---

    @Test
    void accessTypes_containsAllLakeFormationPermissions() {
        Set<String> expected = new HashSet<>(Arrays.asList(
                "select", "insert", "delete", "describe", "alter",
                "drop", "create_database", "create_table", "data_location_access"
        ));

        JsonNode accessTypes = serviceDef.get("accessTypes");
        assertNotNull(accessTypes);

        Set<String> actual = new HashSet<>();
        for (JsonNode at : accessTypes) {
            actual.add(at.get("name").asText());
        }

        assertEquals(expected, actual,
                "Access types should match exactly the 9 Lake Formation permissions");
    }

    @Test
    void accessTypes_haveUniqueItemIds() {
        JsonNode accessTypes = serviceDef.get("accessTypes");
        Set<Integer> ids = new HashSet<>();
        for (JsonNode at : accessTypes) {
            assertTrue(ids.add(at.get("itemId").asInt()),
                    "Duplicate itemId in accessTypes: " + at.get("itemId").asInt());
        }
    }

    @Test
    void accessTypes_allHaveLabels() {
        for (JsonNode at : serviceDef.get("accessTypes")) {
            String name = at.get("name").asText();
            assertTrue(at.has("label") && !at.get("label").asText().isEmpty(),
                    "Access type '" + name + "' should have a non-empty label");
        }
    }

    // --- Requirement 5.3: configuration properties ---

    @Test
    void configs_containRequiredAwsProperties() {
        Set<String> expected = new HashSet<>(Arrays.asList(
                "aws.region", "aws.catalog.id", "aws.access.key",
                "aws.secret.key", "aws.role.arn"
        ));

        JsonNode configs = serviceDef.get("configs");
        assertNotNull(configs);

        Set<String> actual = new HashSet<>();
        for (JsonNode c : configs) {
            actual.add(c.get("name").asText());
        }

        assertEquals(expected, actual,
                "Config properties should include region, catalog ID, credentials, and role ARN");
    }

    @Test
    void configs_regionAndCatalogIdAreMandatory() {
        for (JsonNode c : serviceDef.get("configs")) {
            String name = c.get("name").asText();
            if ("aws.region".equals(name) || "aws.catalog.id".equals(name)) {
                assertTrue(c.get("mandatory").asBoolean(),
                        name + " should be mandatory");
            }
        }
    }

    @Test
    void configs_credentialsAreOptional() {
        for (JsonNode c : serviceDef.get("configs")) {
            String name = c.get("name").asText();
            if ("aws.access.key".equals(name) || "aws.secret.key".equals(name)
                    || "aws.role.arn".equals(name)) {
                assertFalse(c.get("mandatory").asBoolean(),
                        name + " should be optional (supports IAM role auth)");
            }
        }
    }

    @Test
    void configs_secretKeyIsPasswordType() {
        for (JsonNode c : serviceDef.get("configs")) {
            if ("aws.secret.key".equals(c.get("name").asText())) {
                assertEquals("password", c.get("type").asText(),
                        "aws.secret.key should use 'password' type for masking");
                return;
            }
        }
        fail("aws.secret.key config not found");
    }

    @Test
    void configs_haveUniqueItemIds() {
        JsonNode configs = serviceDef.get("configs");
        Set<Integer> ids = new HashSet<>();
        for (JsonNode c : configs) {
            assertTrue(ids.add(c.get("itemId").asInt()),
                    "Duplicate itemId in configs: " + c.get("itemId").asInt());
        }
    }

    // --- General structure ---

    @Test
    void implClass_pointsToResourceLookupService() {
        assertEquals(
                "com.amazonaws.policyconverters.ranger.service.LakeFormationResourceLookupService",
                serviceDef.get("implClass").asText());
    }
}
