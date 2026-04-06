package com.amazonaws.policyconverters.ranger.converter;

import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AccessTypeMapperTest {

    // --- mapAccessType: direct mappings ---

    @Test
    void selectMapsToSelect() {
        assertEquals(EnumSet.of(LFPermission.SELECT), AccessTypeMapper.mapAccessType("select"));
    }

    @Test
    void updateMapsToInsert() {
        assertEquals(EnumSet.of(LFPermission.INSERT), AccessTypeMapper.mapAccessType("update"));
    }

    @Test
    void createMapsToCreateTable() {
        assertEquals(EnumSet.of(LFPermission.CREATE_TABLE), AccessTypeMapper.mapAccessType("create"));
    }

    @Test
    void dropMapsToDrop() {
        assertEquals(EnumSet.of(LFPermission.DROP), AccessTypeMapper.mapAccessType("drop"));
    }

    @Test
    void alterMapsToAlter() {
        assertEquals(EnumSet.of(LFPermission.ALTER), AccessTypeMapper.mapAccessType("alter"));
    }

    // --- mapAccessType: aliases ---

    @Test
    void readMapsToSelect() {
        assertEquals(EnumSet.of(LFPermission.SELECT), AccessTypeMapper.mapAccessType("read"));
    }

    @Test
    void writeMapsToInsert() {
        assertEquals(EnumSet.of(LFPermission.INSERT), AccessTypeMapper.mapAccessType("write"));
    }

    // --- mapAccessType: "all" expansion ---

    @Test
    void allMapsToExpandedSet() {
        Set<LFPermission> expected = EnumSet.of(
                LFPermission.SELECT, LFPermission.INSERT, LFPermission.DELETE,
                LFPermission.ALTER, LFPermission.DROP, LFPermission.DESCRIBE);
        assertEquals(expected, AccessTypeMapper.mapAccessType("all"));
    }

    // --- mapAccessType: case insensitivity ---

    @Test
    void mappingIsCaseInsensitive() {
        assertEquals(EnumSet.of(LFPermission.SELECT), AccessTypeMapper.mapAccessType("SELECT"));
        assertEquals(EnumSet.of(LFPermission.SELECT), AccessTypeMapper.mapAccessType("Select"));
        assertEquals(EnumSet.of(LFPermission.INSERT), AccessTypeMapper.mapAccessType("UPDATE"));
    }

    @Test
    void mappingTrimsWhitespace() {
        assertEquals(EnumSet.of(LFPermission.SELECT), AccessTypeMapper.mapAccessType("  select  "));
    }

    // --- mapAccessType: unknown / null / empty ---

    @Test
    void unknownAccessTypeReturnsEmptySet() {
        Set<LFPermission> result = AccessTypeMapper.mapAccessType("unknown_type");
        assertTrue(result.isEmpty());
    }

    @Test
    void nullAccessTypeReturnsEmptySet() {
        Set<LFPermission> result = AccessTypeMapper.mapAccessType(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyAccessTypeReturnsEmptySet() {
        Set<LFPermission> result = AccessTypeMapper.mapAccessType("");
        assertTrue(result.isEmpty());
    }

    @Test
    void blankAccessTypeReturnsEmptySet() {
        Set<LFPermission> result = AccessTypeMapper.mapAccessType("   ");
        assertTrue(result.isEmpty());
    }

    // --- mapAccessTypes: multiple access types ---

    @Test
    void mapMultipleAccessTypesReturnsUnion() {
        Set<LFPermission> result = AccessTypeMapper.mapAccessTypes(Arrays.asList("select", "alter"));
        assertEquals(EnumSet.of(LFPermission.SELECT, LFPermission.ALTER), result);
    }

    @Test
    void mapMultipleWithOverlappingPermissionsDeduplicates() {
        // "select" and "read" both map to SELECT
        Set<LFPermission> result = AccessTypeMapper.mapAccessTypes(Arrays.asList("select", "read"));
        assertEquals(EnumSet.of(LFPermission.SELECT), result);
    }

    @Test
    void mapMultipleWithUnknownSkipsUnknown() {
        Set<LFPermission> result = AccessTypeMapper.mapAccessTypes(Arrays.asList("select", "bogus", "drop"));
        assertEquals(EnumSet.of(LFPermission.SELECT, LFPermission.DROP), result);
    }

    @Test
    void mapMultipleWithNullCollectionReturnsEmpty() {
        assertTrue(AccessTypeMapper.mapAccessTypes(null).isEmpty());
    }

    @Test
    void mapMultipleWithEmptyCollectionReturnsEmpty() {
        assertTrue(AccessTypeMapper.mapAccessTypes(Collections.<String>emptyList()).isEmpty());
    }

    @Test
    void mapMultipleResultIsUnmodifiable() {
        Set<LFPermission> result = AccessTypeMapper.mapAccessTypes(Arrays.asList("select"));
        assertThrows(UnsupportedOperationException.class, () -> result.add(LFPermission.DELETE));
    }
}
