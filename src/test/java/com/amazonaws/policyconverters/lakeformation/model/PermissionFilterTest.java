package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PermissionFilterTest {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void allFieldsSet() {
        PermissionFilter filter = new PermissionFilter(
                "arn:aws:iam::123456789012:role/Admin",
                "DATABASE",
                Set.of("arn:aws:iam::123456789012:role/Excluded"),
                Set.of("system_db/*"));
        assertEquals("arn:aws:iam::123456789012:role/Admin", filter.getPrincipalArn());
        assertEquals("DATABASE", filter.getResourceType());
        assertEquals(Set.of("arn:aws:iam::123456789012:role/Excluded"), filter.getExcludedPrincipals());
        assertEquals(Set.of("system_db/*"), filter.getExcludedResourcePatterns());
    }

    @Test
    void nullFieldsDefaultToEmptySets() {
        PermissionFilter filter = new PermissionFilter(null, null, null, null);
        assertNull(filter.getPrincipalArn());
        assertNull(filter.getResourceType());
        assertTrue(filter.getExcludedPrincipals().isEmpty());
        assertTrue(filter.getExcludedResourcePatterns().isEmpty());
    }

    @Test
    void shouldExcludeByPrincipal() {
        PermissionFilter filter = new PermissionFilter(
                null, null,
                Set.of("arn:aws:iam::123456789012:role/LFAdmin"),
                Set.of());

        LFPermissionOperation excluded = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, null,
                "arn:aws:iam::123456789012:role/LFAdmin",
                new LFResource("123", "db1", null, null, null),
                EnumSet.of(LFPermission.SELECT), false);

        LFPermissionOperation notExcluded = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, null,
                "arn:aws:iam::123456789012:role/OtherRole",
                new LFResource("123", "db1", null, null, null),
                EnumSet.of(LFPermission.SELECT), false);

        assertTrue(filter.shouldExclude(excluded));
        assertFalse(filter.shouldExclude(notExcluded));
    }

    @Test
    void shouldExcludeByResourcePattern() {
        PermissionFilter filter = new PermissionFilter(
                null, null, Set.of(), Set.of("system_db/*"));

        LFPermissionOperation excluded = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, null,
                "arn:aws:iam::123456789012:role/User",
                new LFResource("123", "system_db", "table1", null, null),
                EnumSet.of(LFPermission.SELECT), false);

        LFPermissionOperation notExcluded = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, null,
                "arn:aws:iam::123456789012:role/User",
                new LFResource("123", "user_db", "table1", null, null),
                EnumSet.of(LFPermission.SELECT), false);

        assertTrue(filter.shouldExclude(excluded));
        assertFalse(filter.shouldExclude(notExcluded));
    }


    @Test
    void shouldExcludeReturnsFalseForNull() {
        PermissionFilter filter = new PermissionFilter(null, null,
                Set.of("arn:aws:iam::123456789012:role/Admin"), Set.of("db/*"));
        assertFalse(filter.shouldExclude(null));
    }

    @Test
    void shouldExcludeReturnsFalseWhenNoFilters() {
        PermissionFilter filter = new PermissionFilter(null, null, null, null);
        LFPermissionOperation op = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, null,
                "arn:aws:iam::123456789012:role/User",
                new LFResource("123", "db1", "t1", null, null),
                EnumSet.of(LFPermission.SELECT), false);
        assertFalse(filter.shouldExclude(op));
    }

    @Test
    void shouldExcludeDataLocationByPattern() {
        PermissionFilter filter = new PermissionFilter(
                null, null, Set.of(), Set.of("s3://bucket/sensitive/*"));

        LFResource dataLoc = new LFResource("123", null, null, null, null,
                "s3://bucket/sensitive/path");
        LFPermissionOperation excluded = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, null,
                "arn:aws:iam::123456789012:role/User",
                dataLoc, EnumSet.of(LFPermission.DATA_LOCATION_ACCESS), false);

        assertTrue(filter.shouldExclude(excluded));
    }

    @Test
    void equalsAndHashCode() {
        PermissionFilter a = new PermissionFilter("p1", "DATABASE",
                Set.of("excl1"), Set.of("pattern1"));
        PermissionFilter b = new PermissionFilter("p1", "DATABASE",
                Set.of("excl1"), Set.of("pattern1"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        PermissionFilter a = new PermissionFilter("p1", "DATABASE", Set.of(), Set.of());
        PermissionFilter b = new PermissionFilter("p2", "DATABASE", Set.of(), Set.of());
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        PermissionFilter original = new PermissionFilter(
                "arn:aws:iam::123456789012:role/Admin", "TABLE",
                Set.of("arn:aws:iam::123456789012:role/Excluded"),
                Set.of("system_db/*"));
        String json = jsonMapper.writeValueAsString(original);
        PermissionFilter deserialized = jsonMapper.readValue(json, PermissionFilter.class);
        assertEquals(original, deserialized);
    }

    @Test
    void yamlRoundTrip() throws Exception {
        PermissionFilter original = new PermissionFilter(
                null, null,
                Set.of("arn:aws:iam::123456789012:role/LFAdmin"),
                Set.of("system_db/*", "temp_*"));
        String yaml = yamlMapper.writeValueAsString(original);
        PermissionFilter deserialized = yamlMapper.readValue(yaml, PermissionFilter.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTripWithNulls() throws Exception {
        PermissionFilter original = new PermissionFilter(null, null, null, null);
        String json = jsonMapper.writeValueAsString(original);
        PermissionFilter deserialized = jsonMapper.readValue(json, PermissionFilter.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringContainsFields() {
        PermissionFilter filter = new PermissionFilter("p1", "DATABASE",
                Set.of("excl1"), Set.of("pattern1"));
        String str = filter.toString();
        assertTrue(str.contains("principalArn='p1'"));
        assertTrue(str.contains("resourceType='DATABASE'"));
        assertTrue(str.contains("excl1"));
        assertTrue(str.contains("pattern1"));
    }

    @Test
    void buildResourcePathDatabase() {
        LFResource resource = new LFResource("123", "mydb", null, null, null);
        assertEquals("mydb", PermissionFilter.buildResourcePath(resource));
    }

    @Test
    void buildResourcePathTable() {
        LFResource resource = new LFResource("123", "mydb", "mytable", null, null);
        assertEquals("mydb/mytable", PermissionFilter.buildResourcePath(resource));
    }

    @Test
    void buildResourcePathDataLocation() {
        LFResource resource = new LFResource("123", null, null, null, null,
                "s3://bucket/path");
        assertEquals("s3://bucket/path", PermissionFilter.buildResourcePath(resource));
    }
}
