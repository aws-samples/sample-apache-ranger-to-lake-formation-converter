package org.apache.ranger.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LFResourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void databaseLevelResource() {
        LFResource resource = new LFResource("123456789012", "mydb", null, null, null);
        assertEquals("123456789012", resource.getCatalogId());
        assertEquals("mydb", resource.getDatabaseName());
        assertNull(resource.getTableName());
        assertNull(resource.getColumnNames());
        assertNull(resource.getRowFilterExpression());
    }

    @Test
    void tableLevelResource() {
        LFResource resource = new LFResource("123456789012", "mydb", "mytable", null, null);
        assertEquals("mytable", resource.getTableName());
    }

    @Test
    void columnLevelResource() {
        Set<String> cols = new HashSet<>(Arrays.asList("col1", "col2"));
        LFResource resource = new LFResource("123456789012", "mydb", "mytable", cols, null);
        assertEquals(cols, resource.getColumnNames());
    }

    @Test
    void columnNamesAreImmutable() {
        Set<String> cols = new HashSet<>(Arrays.asList("col1", "col2"));
        LFResource resource = new LFResource("123456789012", "mydb", "mytable", cols, null);
        assertThrows(UnsupportedOperationException.class, () -> resource.getColumnNames().add("col3"));
    }

    @Test
    void equalsAndHashCode() {
        Set<String> cols = new HashSet<>(Arrays.asList("col1"));
        LFResource a = new LFResource("cat", "db", "tbl", cols, "x > 1");
        LFResource b = new LFResource("cat", "db", "tbl", cols, "x > 1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        LFResource a = new LFResource("cat", "db1", null, null, null);
        LFResource b = new LFResource("cat", "db2", null, null, null);
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        Set<String> cols = new HashSet<>(Arrays.asList("col1", "col2"));
        LFResource original = new LFResource("123456789012", "analytics", "events", cols, "year > 2020");
        String json = mapper.writeValueAsString(original);
        LFResource deserialized = mapper.readValue(json, LFResource.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTripWithNulls() throws Exception {
        LFResource original = new LFResource("123456789012", "analytics", null, null, null);
        String json = mapper.writeValueAsString(original);
        LFResource deserialized = mapper.readValue(json, LFResource.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringContainsKey() {
        LFResource resource = new LFResource("cat", "db", "tbl", null, null);
        String str = resource.toString();
        assertTrue(str.contains("db"));
        assertTrue(str.contains("tbl"));
    }
}
